package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.service.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.sql.BrokerQueryExecutor;
import io.github.sudoitir.artemisstudio.sql.CostRefusedException;
import io.github.sudoitir.artemisstudio.sql.QueryResult;
import io.github.sudoitir.artemisstudio.sql.SqlConsoleService;
import io.github.sudoitir.artemisstudio.sql.SqlSyntaxException;
import io.github.sudoitir.artemisstudio.sql.SqlTailPoller;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import io.github.sudoitir.artemisstudio.sse.Subscriber;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.StreamFrameView;
import io.github.sudoitir.artemisstudio.web.mapper.SqlViewMapper;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/clusters/{id}/sql/stream?sql=…&tail=…} — the console's one
 * execution path (ADR-0058).
 *
 * <p>Per-request delivery does not belong on the cluster stream: its payload depends
 * on parameters this client alone supplied, and no other subscriber shares it. It is
 * therefore its own stream, scoped to the query and ending when the client
 * disconnects — with the same permission check, the same heartbeat and the same
 * subscriber release as {@link StreamController}.
 *
 * <p>Static execution runs here too rather than on a separate POST, so that progress,
 * per-node outcomes and cancellation are one mechanism whether or not the tail is
 * running. Closing the stream is what stops a query: the sink reports itself
 * cancelled and no further broker read is issued.
 *
 * <p>A refusal travels as an {@code error} frame carrying the same
 * {@link ProblemDetail} the JSON API would have returned, not as an HTTP status: an
 * {@code EventSource} cannot read the body of a non-200, and "query refused" with no
 * estimate and no hint is not something an operator can act on.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SqlStreamController {

    private final SqlConsoleService console;
    private final SqlTailPoller tailPoller;
    private final SqlViewMapper mapper;
    private final ClusterAccessGuard clusterAccess;
    private final SseHub hub;
    private final ApiExceptionHandler problems;

    /** Execution runs off the request thread; the emitter is returned immediately. */
    private final ExecutorService queries = Executors.newVirtualThreadPerTaskExecutor();

    @GetMapping(path = "/api/v1/clusters/{clusterId}/sql/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(
            responseCode = "200",
            description = "A stream of row, node, done, tail and failed frames.",
            content = @Content(schema = @Schema(implementation = StreamFrameView.class)))
    public SseEmitter stream(
            @PathVariable UUID clusterId,
            // Defaulted rather than required: a required parameter is rejected by the
            // framework before the permission check runs, which would answer 400 for a
            // cluster the caller cannot see and confirm that it exists.
            @RequestParam(defaultValue = "") String sql,
            @RequestParam(defaultValue = "false") boolean tail,
            HttpServletResponse response) {
        clusterAccess.requireCluster(clusterId, Permissions.MESSAGE_READ);
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(0L);
        // Registered with no topics: it wants nothing the cluster broadcasts, only the
        // heartbeat and the drop-on-write-failure behaviour every stream shares.
        Subscriber subscriber = new Subscriber(emitter, Set.of());
        hub.register(clusterId, subscriber);

        Session session = new Session(emitter);
        emitter.onCompletion(() -> release(clusterId, subscriber, session));
        emitter.onTimeout(() -> release(clusterId, subscriber, session));
        emitter.onError(e -> release(clusterId, subscriber, session));

        // The permission check and the audit actor both read the security context, and
        // a virtual thread starts with an empty one.
        SecurityContext security = SecurityContextHolder.getContext();
        queries.execute(() -> {
            SecurityContextHolder.setContext(security);
            try {
                run(clusterId, sql, tail, session);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    private void run(UUID clusterId, String sql, boolean tail, Session session) {
        SqlConsoleService.Executed executed;
        try {
            executed = console.run(clusterId, sql, session);
        } catch (RuntimeException e) {
            session.send("failed", problemFor(e));
            session.complete();
            return;
        }
        session.send("done", mapper.toView(executed.result(), executed.plan()));
        if (!tail || session.cancelled) {
            session.complete();
            return;
        }
        SqlTailPoller.Tail running =
                tailPoller.start(clusterId, executed.plan(), console.transportFor(clusterId), session);
        // The static rows are already on the client. Advancing the mark past them is
        // what stops the first poll from sending every one of them again.
        executed.result().rows().forEach(running::seen);
        session.tail = running;
    }

    private void release(UUID clusterId, Subscriber subscriber, Session session) {
        hub.remove(clusterId, subscriber);
        session.cancelled = true;
        if (session.tail != null) {
            session.tail.stop();
        }
    }

    /**
     * The same problem the JSON API would have returned. Dispatching by type here
     * rather than rebuilding the payloads keeps one definition of what a refused query
     * says, so the console renders a refusal identically whichever path produced it.
     */
    private ProblemDetail problemFor(RuntimeException e) {
        if (e instanceof SqlSyntaxException syntax) {
            return problems.onSqlSyntax(syntax);
        }
        if (e instanceof CostRefusedException refused) {
            return problems.onCostRefused(refused);
        }
        if (e instanceof SqlConsoleService.TooManyQueriesException tooMany) {
            return problems.onTooManyQueries(tooMany);
        }
        log.debug("SQL console query failed", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        problem.setTitle("The query could not be run");
        return problem;
    }

    /**
     * One open console stream: the sink for the static run, the listener for the tail,
     * and the thing that knows the client is gone.
     */
    private final class Session implements BrokerQueryExecutor.Sink, SqlTailPoller.Listener {

        private final SseEmitter emitter;
        private volatile boolean cancelled;
        private volatile SqlTailPoller.Tail tail;

        private Session(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void row(QueryResult.Row row) {
            send("row", mapper.toView(row));
        }

        @Override
        public void nodeFinished(QueryResult.NodeOutcome outcome) {
            send("node", mapper.toView(outcome));
        }

        @Override
        public void status(SqlTailPoller.TailStatus status) {
            send("tail", mapper.toView(status));
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        private void send(String name, Object payload) {
            if (cancelled) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
            } catch (IOException | RuntimeException e) {
                // A write failure is how a disconnect is discovered, so it is the
                // signal to stop rather than something to report.
                cancelled = true;
            }
        }

        private void complete() {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
    }
}

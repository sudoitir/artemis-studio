package io.github.sudoitir.artemisstudio.kernel.stream.web;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.artemisstudio.kernel.stream.Subscriber;
import io.github.sudoitir.artemisstudio.kernel.stream.UserStreamHub;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/me/stream}: the signed-in user's own stream, which tells the console to refetch the
 * inbox and the held operations. At most {@value UserStreamHub#MAX_STREAMS_PER_USER} streams per user and
 * replica; a further one ends the oldest. A replica that is draining answers 503 so the client retries
 * elsewhere.
 */
@RestController
public class UserStreamController {

    private final UserStreamHub hub;
    private final ReplicaRegistry replica;

    public UserStreamController(UserStreamHub hub, ReplicaRegistry replica) {
        this.hub = hub;
        this.replica = replica;
    }

    @GetMapping(path = "/me/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request, HttpServletResponse response) {
        if (replica.state() == ReplicaRegistry.State.DRAINING || replica.state() == ReplicaRegistry.State.STOPPED) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This replica is shutting down");
        }
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = Subscriber.of(emitter, Set.of(), request);
        if (subscriber.principal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to open your stream");
        }
        var userId = subscriber.principal().userId();
        response.setHeader("X-Accel-Buffering", "no");
        hub.register(userId, subscriber);
        emitter.onCompletion(() -> hub.remove(userId, subscriber));
        emitter.onTimeout(() -> hub.remove(userId, subscriber));
        emitter.onError(e -> hub.remove(userId, subscriber));
        hub.greet(subscriber);
        return emitter;
    }
}

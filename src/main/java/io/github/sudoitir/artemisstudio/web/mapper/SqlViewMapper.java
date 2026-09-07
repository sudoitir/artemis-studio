package io.github.sudoitir.artemisstudio.web.mapper;

import io.github.sudoitir.artemisstudio.sql.QueryPlan;
import io.github.sudoitir.artemisstudio.sql.QueryResult;
import io.github.sudoitir.artemisstudio.sql.SqlTailPoller;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.BoundView;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.NoticeView;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.PlanView;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.ResultView;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.RowView;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.SqlNodeOutcomeView;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.TailStatusView;
import io.github.sudoitir.artemisstudio.web.dto.SqlViews.TargetView;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Domain to wire for the SQL Console. Hand-written rather than generated: every
 * conversion here is an enum to a stable string the frontend switches on, and
 * writing them out is clearer than configuring them.
 */
@Component
public class SqlViewMapper {

    public PlanView toView(QueryPlan plan) {
        return new PlanView(
                plan.resolvedSource().name(),
                plan.targets().stream()
                        .map(t ->
                                new TargetView(t.nodeId(), t.nodeName(), t.queueName(), t.address(), t.messageCount()))
                        .toList(),
                plan.selector(),
                plan.requiresScan(),
                plan.pushedDown(),
                plan.scanned(),
                plan.estimatedMessagesExamined(),
                plan.effectiveLimit(),
                notices(plan.notices()));
    }

    public ResultView toView(QueryResult result, QueryPlan plan) {
        return new ResultView(
                result.nodes().stream().map(this::toView).toList(),
                result.boundsReached().stream()
                        .map(b -> new BoundView(b.kind().name(), b.value()))
                        .toList(),
                notices(result.notices()),
                result.isPartial(),
                toView(plan));
    }

    public RowView toView(QueryResult.Row row) {
        return new RowView(
                row.nodeId(),
                row.nodeName(),
                row.queueName(),
                row.address(),
                row.messageId(),
                row.messageType(),
                row.durable(),
                row.priority(),
                row.timestamp(),
                row.expiration(),
                row.size(),
                row.jmsType(),
                row.correlationId(),
                row.groupId(),
                row.userId(),
                row.replyTo(),
                row.body(),
                row.bodyTruncated(),
                row.properties(),
                row.source().name(),
                text(row.observedAt()),
                text(row.lastSeenAt()));
    }

    public SqlNodeOutcomeView toView(QueryResult.NodeOutcome outcome) {
        return new SqlNodeOutcomeView(
                outcome.nodeId(),
                outcome.nodeName(),
                outcome.queueName(),
                outcome.status().name(),
                outcome.examined(),
                outcome.matched(),
                outcome.servedBy() == null ? null : outcome.servedBy().name(),
                outcome.detail());
    }

    public TailStatusView toView(SqlTailPoller.TailStatus status) {
        return new TailStatusView(
                status.enqueued(),
                status.shown(),
                status.polls(),
                text(status.lastPollAt()),
                status.everyMessageMatches());
    }

    private List<NoticeView> notices(List<QueryPlan.Notice> notices) {
        return notices.stream()
                .map(n -> new NoticeView(n.kind().name(), n.detail()))
                .toList();
    }

    private String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}

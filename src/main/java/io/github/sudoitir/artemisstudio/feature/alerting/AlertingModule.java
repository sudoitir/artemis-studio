package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.TopicDef;
import java.util.List;

/** Alert rules, firings and notification channels. Module descriptor (ADR-0070). */
public final class AlertingModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("alerting")
            .title("Alerting")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(new PermissionDef(AlertPermissions.ALERT_READ, "View alert rules and firings"))
            .permission(
                    new PermissionDef(AlertPermissions.ALERT_WRITE, "Create, edit, or delete alert rules and channels"))
            .apiPrefix("/api/v1/clusters/{clusterId}/alerts")
            .apiPrefix("/api/v1/alerts")
            .apiPrefix("/api/v1/channels")
            .settingKey(AlertingSettings.DISPATCH_INTERVAL)
            .settingKey(AlertingSettings.MAX_ATTEMPTS)
            .settingKey(AlertingSettings.INITIAL_BACKOFF)
            .settingKey(AlertingSettings.MAX_BACKOFF)
            .streamTopic(TopicDef.signal("alerts"))
            .mcpTool(new McpToolDef(
                    "alert_rule",
                    McpToolDef.Posture.MUTATE,
                    "List, create, update or delete alert rules.",
                    List.of(
                            McpToolDef.Param.values(
                                    "op", List.of("list", "create", "update", "delete"), "Default list."),
                            McpToolDef.Param.shape(
                                    "rule",
                                    "{ name, kind, metric, comparator, threshold, stateCondition, forSeconds, "
                                            + "severity, scope, enabled }",
                                    "Required for create; a partial body is merged on update."),
                            McpToolDef.Param.note("confirm", "The rule name, to delete."))))
            .build();

    private AlertingModule() {}
}

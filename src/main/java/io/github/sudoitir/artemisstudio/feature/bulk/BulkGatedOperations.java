package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.feature.bulk.BulkService.BulkExecuteParams;
import io.github.sudoitir.artemisstudio.feature.bulk.BulkService.Reach;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.artemisstudio.kernel.gate.DisplayRow;
import io.github.sudoitir.artemisstudio.kernel.gate.Effect;
import io.github.sudoitir.artemisstudio.kernel.gate.ExecutionMode;
import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperation;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationScope;
import io.github.sudoitir.artemisstudio.kernel.gate.Trait;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterEnvironmentIndex;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated {@code bulk.execute} (ADR-0179): a bulk run is held as one request, and its queues run covered by it once
 * it is approved. A run that destroys messages is also destructive. Its estimate is the previewed plan, and the plan's
 * hash is the state key, so a plan that changed is refused when it would start.
 */
@Configuration(proxyBeanMethods = false)
class BulkGatedOperations {

    @Bean
    GatedOperation<BulkExecuteParams> bulkExecuteOperation(BulkService bulk, ClusterEnvironmentIndex clusters) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "bulk.execute";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<BulkExecuteParams> paramsType() {
                return BulkExecuteParams.class;
            }

            @Override
            public Set<Trait> traits(BulkExecuteParams p) {
                return p.operation().destructive() ? EnumSet.of(Trait.BULK, Trait.DESTRUCTIVE) : EnumSet.of(Trait.BULK);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(BulkExecuteParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(BulkExecuteParams p) {
                return verb(p.operation()) + " " + p.queues() + (p.queues() == 1 ? " queue" : " queues") + " on "
                        + clusters.labelOf(p.clusterId());
            }

            @Override
            public List<DisplayRow> display(BulkExecuteParams p) {
                return List.of(
                        DisplayRow.of("Cluster", clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Operation", p.operation().name().toLowerCase(Locale.ROOT)),
                        DisplayRow.of("Queues", String.valueOf(p.queues())),
                        DisplayRow.of("Continue after a failure", p.continueOnFailure() ? "yes" : "no"));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(BulkExecuteParams p) {
                Reach reach = bulk.reach(p.clusterId(), p.runId());
                String detail = null;
                if (reach.operation().destructive()) {
                    String ending = reach.complete() ? "." : ", some unknown.";
                    detail = "About " + reach.messages() + " messages" + ending;
                }
                return new Effect(reach.queues(), "queues", reach.planHash(), detail);
            }

            @Override
            public void replay(BulkExecuteParams p) {
                bulk.execute(
                        p.clusterId(),
                        p.runId(),
                        new BulkExecuteRequest(p.planHash(), p.override(), p.continueOnFailure()));
            }
        };
    }

    private static String verb(BulkOperation operation) {
        return switch (operation) {
            case PURGE -> "Purge";
            case DELETE -> "Delete";
            case PAUSE -> "Pause";
            case RESUME -> "Resume";
        };
    }
}

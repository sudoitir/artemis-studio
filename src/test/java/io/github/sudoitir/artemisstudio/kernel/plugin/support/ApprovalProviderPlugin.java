package io.github.sudoitir.artemisstudio.kernel.plugin.support;

import java.util.List;
import java.util.Map;

/** A fixture plugin that is the approval provider and contributes one gated operation, {@code <id>:thing}. */
public final class ApprovalProviderPlugin {

    private ApprovalProviderPlugin() {}

    /** The provider plugin {@code pluginId} at {@code version}, which allows everything. */
    public static PluginJarBuilder jar(String pluginId, String version) {
        PluginJarBuilder builder = new PluginJarBuilder(pluginId).descriptorField("version", version);
        return withProviderBlock(builder, pluginId)
                .source(builder.basePackage() + ".Provider", provider(builder.basePackage()))
                .source(builder.basePackage() + ".ThingParams", """
                        package %s;
                        public record ThingParams(String name) {}
                        """.formatted(builder.basePackage()))
                .source(builder.basePackage() + ".ThingOperation", operation(builder.basePackage(), pluginId));
    }

    /** Declares {@code approvalProvider} and the permission it names, without any bean. */
    public static PluginJarBuilder withProviderBlock(PluginJarBuilder builder, String pluginId) {
        return builder.descriptorField(
                        "permissions",
                        List.of(Map.of("action", pluginId + ":approve", "description", "Approve", "scope", "global")))
                .descriptorField("approvalProvider", Map.of("approverPermission", pluginId + ":approve"));
    }

    private static String provider(String basePackage) {
        return """
                package %s;
                import io.github.sudoitir.artemisstudio.kernel.gate.*;
                import org.springframework.stereotype.Component;
                @Component
                public class Provider implements ApprovalProvider {
                    public GateDecision decide(GateRequest request) {
                        return new GateDecision.Allow(new PolicyRef("always", "1", null));
                    }
                    public VoteCheck checkVote(HeldOperationView held, Approver approver, Vote vote) {
                        return VoteCheck.allow();
                    }
                    public RunCheck checkRun(HeldOperationView held, Effect now) {
                        return RunCheck.allow();
                    }
                }
                """.formatted(basePackage);
    }

    private static String operation(String basePackage, String pluginId) {
        return """
                package %s;
                import io.github.sudoitir.artemisstudio.kernel.gate.*;
                import java.util.List;
                import java.util.Set;
                import org.springframework.stereotype.Component;
                @Component
                public class ThingOperation implements GatedOperation<ThingParams> {
                    public String type() { return "%s:thing"; }
                    public int version() { return 1; }
                    public Class<ThingParams> paramsType() { return ThingParams.class; }
                    public Set<Trait> traits(ThingParams p) { return Set.of(); }
                    public ExecutionMode mode() { return ExecutionMode.ON_APPROVAL; }
                    public OperationScope scope(ThingParams p) { return OperationScope.GLOBAL; }
                    public String summary(ThingParams p) { return "Thing " + p.name(); }
                    public List<DisplayRow> display(ThingParams p) { return List.of(); }
                    public Set<String> redactedPaths() { return Set.of(); }
                    public Effect estimate(ThingParams p) { return new Effect(1, "thing", "k", null); }
                    public void replay(ThingParams p) {}
                }
                """.formatted(basePackage, pluginId);
    }
}

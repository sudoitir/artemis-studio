package io.github.sudoitir.artemisstudio.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceFilter;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceRef;
import io.github.sudoitir.artemisstudio.platform.clusters.BrokerCommands;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * An operation that takes the name of a queue or an address decides, somewhere on its way, whether the caller
 * may do it to that resource (resource-permissions spec): it calls a resource check ({@code
 * ClusterAccessGuard.requireResource} and its kin, {@code PermissionResolver.can} on a {@code ResourceRef}, a
 * {@code ResourceFilter}), or a method that does. A public method of a service, controller or tool that takes
 * such a name and reaches no check fails here, unless it is listed in {@link #EXEMPT} with the reason.
 *
 * <p>A call to {@link BrokerCommands#run} is not a check: it checks what its command lists as {@code
 * resources}, so the call has to set them. An exemption for a method that no longer takes such a name, or now
 * reaches a check, fails too, so the list cannot outlive its cause.
 */
class ResourceCheckCoverageTest {

    private static final String BASE = "io.github.sudoitir.artemisstudio.";

    /** A parameter that names a queue or an address. */
    private static final Pattern NAMES_A_RESOURCE =
            Pattern.compile("(?i)(.*queue(name)?|.*address(name)?|source|target)");

    private static final Set<String> STEREOTYPES = Set.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.stereotype.Controller");

    /** Methods that decide for one resource, a pattern of them, or a list of them. */
    private static final Map<String, Set<String>> CHECKS = Map.of(
            ClusterAccessGuard.class.getName(),
            Set.of("requireResource", "requireAll", "requireCreate", "requireOnAll", "mayAll", "mayOnAll"),
            PermissionResolver.class.getName(),
            Set.of("filter", "can", "canOnAll"),
            ResourceFilter.class.getName(),
            Set.of("readable", "allowedActions"),
            OperatorHandoff.class.getName(),
            Set.of("stillHolds"),
            BrokerCommands.class.getName() + "$Command$CommandBuilder",
            Set.of("resources"));

    /** Not traversed: the checks above, and the runner of commands, whose check is what the command lists. */
    private static final Set<String> OPAQUE = Set.of(BrokerCommands.class.getName());

    /**
     * Methods, or whole classes (no method named), that take such a name and need no check of their own, each with
     * why. They are the broker primitives a checked service calls, work Studio does for itself, and text.
     */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            Map.entry(
                    "feature.queues.QueueLifecycleOperations",
                    "broker primitives; QueueLifecycleService checks before it calls them"),
            Map.entry(
                    "feature.queues.DivertOperations",
                    "broker primitives; QueueLifecycleService and RoutingService check before they call them"),
            Map.entry(
                    "feature.brokerconfig.BrokerConfigOperations",
                    "applies declared broker configuration, a cluster-level change that BrokerConfigService gates on"
                            + " config:write and config:apply"),
            Map.entry(
                    "feature.resources.ConnectionOperations",
                    "broker primitives; connection control is a cluster-level permission checked by its callers"),
            Map.entry(
                    "platform.broker.MessageOperations",
                    "broker primitives; MessageService, DlqService and the transfer and bulk runners check before"
                            + " they call them"),
            Map.entry("platform.broker.StagingQueues", "a transfer's own staging queues, made and removed by its run"),
            Map.entry(
                    "platform.broker.AcceptanceProbe",
                    "reads a target's facts for a transfer that was already checked"),
            Map.entry(
                    "platform.scrape.QueueLocator",
                    "finds where a name is on the scraped snapshot; its callers have checked the name"),
            Map.entry(
                    "platform.scrape.QueueSnapshotUpsert",
                    "the scrape's own bookkeeping, driven by the broker and no caller"),
            Map.entry(
                    "feature.rr.QueueTargetResolver",
                    "the request-reply sampler's own lookup; no caller names a queue"),
            Map.entry("feature.rr.ReplyAddressResolver", "matches a declared reply pattern for the sampler"),
            Map.entry("feature.rr.RrMetrics.recordCompletion", "records a latency the sampler observed"),
            Map.entry("feature.rr.RrSamplerHealth", "the sampler's own health record"),
            Map.entry(
                    "feature.sql.CaptureCoverage", "measures what capture covers for the index view, after its check"),
            Map.entry("feature.sql.CaptureTapCoverage", "decides what a capture tap covers; no caller names a queue"),
            Map.entry("feature.sql.MessageIndexWriter", "indexes what capture receives, for Studio and no caller"),
            Map.entry(
                    "feature.sql.SqlGovernance.forEvaluation",
                    "masks a message for a query that was checked against its queues"),
            Map.entry(
                    "feature.sql.SqlGovernance.forStorage",
                    "masks a message for the index; storage is Studio's, and masked for everyone"),
            Map.entry(
                    "platform.governance.ContentPolicy.governForStorage",
                    "masks a message for storage, which is masked for everyone"),
            Map.entry(
                    "platform.mcp.McpRunbookPrompts",
                    "returns a fixed procedure that repeats the name it was given; it reads nothing"),
            Map.entry(
                    "feature.identitylocal.mfa.SecondFactorService.trustDevice",
                    "'source' is where a sign-in came from"));

    @Test
    void everyMethodThatTakesAQueueOrAddressNameReachesAResourceCheck() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.sudoitir.artemisstudio");
        Set<String> unchecked = new TreeSet<>();
        Set<String> exempt = new TreeSet<>();
        for (JavaClass c : classes) {
            if (!isEntryPoint(c)) {
                continue;
            }
            for (JavaMethod m : c.getMethods()) {
                if (!m.getModifiers().contains(JavaModifier.PUBLIC)
                        || m.getModifiers().contains(JavaModifier.SYNTHETIC)
                        || !takesAResourceName(m)) {
                    continue;
                }
                String className = c.getName().substring(BASE.length());
                String id = className + "." + m.getName();
                boolean checked = reachesACheck(m);
                String exemption = EXEMPT.containsKey(id) ? id : EXEMPT.containsKey(className) ? className : null;
                if (exemption != null) {
                    exempt.add(exemption);
                    id = exemption;
                    if (checked) {
                        unchecked.add(id + " is exempt but reaches a check; remove the exemption");
                    }
                } else if (!checked) {
                    unchecked.add(id);
                }
            }
        }
        assertThat(unchecked)
                .as("check the caller against the queue or address, or add a reasoned exemption")
                .isEmpty();
        assertThat(exempt)
                .as("an exemption for a method that takes no such name any more")
                .containsExactlyInAnyOrderElementsOf(EXEMPT.keySet());
    }

    private static boolean isEntryPoint(JavaClass c) {
        return c.getName().startsWith(BASE)
                && c.getAnnotations().stream()
                        .anyMatch(a -> STEREOTYPES.contains(a.getRawType().getName()));
    }

    private static boolean takesAResourceName(JavaMethod method) {
        Parameter[] parameters = method.reflect().getParameters();
        for (Parameter p : parameters) {
            if (p.getType() == String.class
                    && NAMES_A_RESOURCE.matcher(p.getName()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a check is reached from the method through calls, method references and lambdas of the code base. */
    private static boolean reachesACheck(JavaMethod start) {
        Deque<JavaCodeUnit> pending = new ArrayDeque<>(List.of(start));
        Set<JavaCodeUnit> seen = new HashSet<>(pending);
        while (!pending.isEmpty()) {
            JavaCodeUnit unit = pending.poll();
            List<JavaAccess<?>> accesses = new ArrayList<>(unit.getMethodCallsFromSelf());
            accesses.addAll(unit.getMethodReferencesFromSelf());
            for (JavaAccess<?> access : accesses) {
                if (isCheck(access)) {
                    return true;
                }
                for (JavaCodeUnit callee : targetsOf(access)) {
                    if (seen.add(callee)) {
                        pending.add(callee);
                    }
                }
            }
            // The body of a lambda is a method of its own that nothing calls by name.
            for (JavaMethod lambda : unit.getOwner().getMethods()) {
                if (lambda.getName().startsWith("lambda$" + unit.getName() + "$") && seen.add(lambda)) {
                    pending.add(lambda);
                }
            }
        }
        return false;
    }

    private static boolean isCheck(JavaAccess<?> access) {
        Set<String> names = CHECKS.get(access.getTargetOwner().getName());
        if (names == null || !names.contains(access.getName())) {
            return false;
        }
        // The resolver's can(cluster, permission) asks about the cluster; only the overloads on a resource count.
        return !access.getTargetOwner().getName().equals(PermissionResolver.class.getName())
                || !access.getName().equals("can")
                || ((com.tngtech.archunit.core.domain.JavaCodeUnitAccess<?>) access)
                        .getTarget().getRawParameterTypes().stream()
                                .anyMatch(t -> t.getName().equals(ResourceRef.class.getName()));
    }

    /** The code a call can run: the method itself, and what implements it when it is declared abstractly. */
    private static List<JavaCodeUnit> targetsOf(JavaAccess<?> access) {
        JavaClass owner = access.getTargetOwner();
        if (!owner.getName().startsWith(BASE) || OPAQUE.contains(owner.getName())) {
            return List.of();
        }
        List<JavaCodeUnit> targets = new ArrayList<>();
        for (JavaClass type : concreteTypes(owner)) {
            type.getMethods().stream()
                    .filter(m -> m.getName().equals(access.getName()))
                    .forEach(targets::add);
        }
        return targets;
    }

    private static List<JavaClass> concreteTypes(JavaClass owner) {
        List<JavaClass> types = new ArrayList<>(List.of(owner));
        types.addAll(owner.getAllSubclasses());
        return types;
    }
}

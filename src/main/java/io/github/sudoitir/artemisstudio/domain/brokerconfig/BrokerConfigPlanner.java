package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig.AddressUsage;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Finding;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.FindingKind;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Hazard;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.HazardKind;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.NodePlan;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Op;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Section;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.Plan.Step;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turns a declaration and one observed read per node into the ordered steps an apply
 * would take, the hazards those steps carry, and the findings nobody should act on
 * (ADR-0067 D3–D7). Pure: no broker, no database, no clock.
 *
 * <p>The same computation serves drift: an {@code ADD} step that nobody applies is a
 * missing resource, a {@code REPLACE} step is a divergent one. The drift service reads
 * the steps; it does not re-derive them.
 *
 * <p>Order within a node is dependency order — addresses, queues, address settings,
 * security settings, diverts — and removals come last in reverse, so a divert never
 * points at an address that does not yet exist and a setting is never removed from
 * under a resource that still needs it.
 */
public final class BrokerConfigPlanner {

    /** The management and notification addresses a security match must not cover carelessly. */
    private static final List<String> PROTECTED_ADDRESSES = List.of("activemq.management", "activemq.notifications");

    private static final String DEFAULT_MATCH = "#";

    private BrokerConfigPlanner() {}

    public static Plan plan(
            BrokerConfigDocument doc, List<ObservedNodeConfig> observed, Set<OwnedItem> owned, PlanOptions options) {
        List<Violation> violations = new ArrayList<>(BrokerConfigValidator.validate(doc));
        List<ObservedNodeConfig> readable =
                observed.stream().filter(ObservedNodeConfig::readable).toList();
        referential(doc, readable, violations);
        if (!violations.isEmpty()) {
            return new Plan(List.of(), List.of(), List.of(), violations, "", 0, null);
        }

        List<NodePlan> nodes = new ArrayList<>();
        List<Hazard> hazards = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Set<UUID> targeted = options.nodeIds();

        for (ObservedNodeConfig node : observed.stream()
                .sorted(Comparator.comparing(ObservedNodeConfig::nodeName, Comparator.nullsLast(String::compareTo)))
                .toList()) {
            if (!node.live()) {
                nodes.add(new NodePlan(node.nodeId(), node.nodeName(), false, null, List.of()));
                findings.add(new Finding(
                        FindingKind.NOT_EVALUATED,
                        node.nodeId(),
                        node.nodeName(),
                        null,
                        null,
                        "Not live. A backup inherits what its primary holds once it becomes active."));
                continue;
            }
            if (!targeted.isEmpty() && !targeted.contains(node.nodeId())) {
                continue;
            }
            if (node.unavailableReason() != null) {
                nodes.add(new NodePlan(node.nodeId(), node.nodeName(), true, node.unavailableReason(), List.of()));
                findings.add(new Finding(
                        FindingKind.UNREACHABLE, node.nodeId(), node.nodeName(), null, null, node.unavailableReason()));
                continue;
            }
            List<Step> steps = new ArrayList<>();
            NodeContext ctx = new NodeContext(node, doc, owned, options, steps, hazards, findings);
            ctx.addresses();
            ctx.queues();
            ctx.addressSettings();
            ctx.securitySettings();
            ctx.diverts();
            ctx.removals();
            ctx.undeclared();
            nodes.add(new NodePlan(node.nodeId(), node.nodeName(), true, null, steps));
        }

        UUID canary = canary(nodes, options.canaryNodeId());
        int stepCount = (int) nodes.stream().mapToLong(NodePlan::pendingSteps).sum();
        return new Plan(nodes, hazards, findings, List.of(), hash(nodes), stepCount, canary);
    }

    // ---- referential checks that need the observed cluster ------------------

    private static void referential(BrokerConfigDocument doc, List<ObservedNodeConfig> nodes, List<Violation> out) {
        Set<String> declaredAddresses = new HashSet<>();
        Set<String> declaredAddressesWithQueues = new HashSet<>();
        for (AddressDecl a : doc.addresses()) {
            declaredAddresses.add(a.name());
            if (!a.queues().isEmpty()) {
                declaredAddressesWithQueues.add(a.name());
            }
        }
        for (int i = 0; i < doc.addressSettings().size(); i++) {
            AddressSettingDecl s = doc.addressSettings().get(i);
            String p = "addressSettings[" + i + "].values.";
            check(
                    s.values().get(AddressSettingKey.DEAD_LETTER_ADDRESS.jsonName()),
                    s,
                    AddressSettingKey.AUTO_CREATE_DEAD_LETTER_RESOURCES,
                    declaredAddresses,
                    nodes,
                    p + AddressSettingKey.DEAD_LETTER_ADDRESS.jsonName(),
                    "dead-letter address",
                    out);
            check(
                    s.values().get(AddressSettingKey.EXPIRY_ADDRESS.jsonName()),
                    s,
                    AddressSettingKey.AUTO_CREATE_EXPIRY_RESOURCES,
                    declaredAddresses,
                    nodes,
                    p + AddressSettingKey.EXPIRY_ADDRESS.jsonName(),
                    "expiry address",
                    out);
            pageSizeAgainstMerged(s, nodes, "addressSettings[" + i + "].values.", out);
        }
        for (int i = 0; i < doc.diverts().size(); i++) {
            DivertDecl d = doc.diverts().get(i);
            if (d.forwardingAddress() == null) {
                continue;
            }
            boolean declared = declaredAddressesWithQueues.contains(d.forwardingAddress());
            boolean observedEverywhere = !nodes.isEmpty()
                    && nodes.stream()
                            .allMatch(n -> n.queues().values().stream()
                                    .anyMatch(q -> d.forwardingAddress().equals(q.get("address"))));
            if (!declared && !observedEverywhere) {
                out.add(new Violation(
                        "diverts[" + i + "].forwardingAddress",
                        "Nothing consumes at '" + d.forwardingAddress()
                                + "': declare a queue on it or it must exist with a queue on every live node."
                                + " Messages diverted to an address without a queue are dropped."));
            }
        }
    }

    private static void check(
            Object target,
            AddressSettingDecl s,
            AddressSettingKey autoCreateKey,
            Set<String> declaredAddresses,
            List<ObservedNodeConfig> nodes,
            String path,
            String what,
            List<Violation> out) {
        if (!(target instanceof String address) || address.isBlank()) {
            return;
        }
        if (declaredAddresses.contains(address)) {
            return;
        }
        Object autoCreate = s.values().get(autoCreateKey.jsonName());
        if (autoCreate != null && Boolean.parseBoolean(autoCreate.toString())) {
            return;
        }
        boolean observedEverywhere = !nodes.isEmpty()
                && nodes.stream()
                        .allMatch(n -> n.addresses().containsKey(address)
                                || Boolean.TRUE.equals(Values.normalise(
                                        autoCreateKey,
                                        n.addressSettings()
                                                .getOrDefault(s.match(), Map.of())
                                                .get(autoCreateKey.jsonName()))));
        if (!observedEverywhere) {
            out.add(new Violation(
                    path,
                    "The " + what + " '" + address + "' is neither declared nor present on every live node, and "
                            + autoCreateKey.xmlName() + " is off. Declare the address, or enable "
                            + autoCreateKey.xmlName() + "."));
        }
    }

    private static void pageSizeAgainstMerged(
            AddressSettingDecl s, List<ObservedNodeConfig> nodes, String pathPrefix, List<Violation> out) {
        BigDecimal declaredMax =
                BrokerConfigValidator.number(s.values().get(AddressSettingKey.MAX_SIZE_BYTES.jsonName()));
        BigDecimal declaredPage =
                BrokerConfigValidator.number(s.values().get(AddressSettingKey.PAGE_SIZE_BYTES.jsonName()));
        if ((declaredMax == null) == (declaredPage == null)) {
            return; // both declared is the validator's check; neither declared is nothing to check
        }
        for (ObservedNodeConfig n : nodes) {
            Map<String, Object> merged = n.addressSettings()
                    .getOrDefault(s.match(), n.addressSettings().getOrDefault(DEFAULT_MATCH, Map.of()));
            BigDecimal max = declaredMax != null
                    ? declaredMax
                    : BrokerConfigValidator.number(merged.get(AddressSettingKey.MAX_SIZE_BYTES.jsonName()));
            BigDecimal page = declaredPage != null
                    ? declaredPage
                    : BrokerConfigValidator.number(merged.get(AddressSettingKey.PAGE_SIZE_BYTES.jsonName()));
            if (max != null && page != null && max.signum() >= 0 && page.compareTo(max) >= 0) {
                out.add(new Violation(
                        pathPrefix + (declaredMax != null ? "maxSizeBytes" : "pageSizeBytes"),
                        "On " + n.nodeName() + " the merged page-size-bytes (" + page
                                + ") is not lower than max-size-bytes (" + max
                                + "); the broker refuses the pair. Declare both."));
                return;
            }
        }
    }

    // ---- per-node planning ---------------------------------------------------

    private record NodeContext(
            ObservedNodeConfig node,
            BrokerConfigDocument doc,
            Set<OwnedItem> owned,
            PlanOptions options,
            List<Step> steps,
            List<Hazard> hazards,
            List<Finding> findings) {

        Map<String, Object> base() {
            return node.addressSettings().getOrDefault(DEFAULT_MATCH, Map.of());
        }

        void addresses() {
            for (AddressDecl a : doc.addresses()) {
                Set<String> observed = node.addresses().get(a.name());
                Map<String, Object> after = Map.of("routingTypes", List.copyOf(a.routingTypes()));
                if (observed == null) {
                    steps.add(new Step(
                            id(Section.ADDRESS, a.name(), Op.ADD),
                            Op.ADD,
                            Section.ADDRESS,
                            a.name(),
                            Map.of(),
                            after,
                            false,
                            "Create address " + a.name() + " (" + String.join(", ", a.routingTypes()) + ")"));
                } else if (observed.containsAll(a.routingTypes())) {
                    steps.add(new Step(
                            id(Section.ADDRESS, a.name(), Op.ADD),
                            Op.ADD,
                            Section.ADDRESS,
                            a.name(),
                            Map.of("routingTypes", List.copyOf(observed)),
                            after,
                            true,
                            "Address " + a.name() + " exists"));
                } else {
                    findings.add(new Finding(
                            FindingKind.DIVERGENT_ADDRESS,
                            node.nodeId(),
                            node.nodeName(),
                            Section.ADDRESS,
                            a.name(),
                            "Exists with routing types " + String.join(", ", observed) + "; declared "
                                    + String.join(", ", a.routingTypes())
                                    + ". An apply does not change an existing address's routing types."));
                }
            }
        }

        void queues() {
            for (AddressDecl a : doc.addresses()) {
                for (QueueDecl q : a.queues()) {
                    Map<String, Object> wanted = queueConfig(a.name(), q);
                    Map<String, Object> existing = node.queues().get(q.name());
                    if (existing == null) {
                        steps.add(new Step(
                                id(Section.QUEUE, q.name(), Op.ADD),
                                Op.ADD,
                                Section.QUEUE,
                                q.name(),
                                Map.of(),
                                wanted,
                                false,
                                "Create queue " + q.name() + " on " + a.name()));
                        continue;
                    }
                    List<String> differing = new ArrayList<>();
                    for (Map.Entry<String, Object> e : wanted.entrySet()) {
                        if (e.getKey().equals("auto-create-address")) {
                            continue;
                        }
                        if (!Values.same(e.getValue(), existing.get(e.getKey()))) {
                            differing.add(e.getKey());
                        }
                    }
                    if (differing.isEmpty()) {
                        steps.add(new Step(
                                id(Section.QUEUE, q.name(), Op.ADD),
                                Op.ADD,
                                Section.QUEUE,
                                q.name(),
                                existing,
                                wanted,
                                true,
                                "Queue " + q.name() + " exists as declared"));
                    } else {
                        findings.add(new Finding(
                                FindingKind.DIVERGENT_QUEUE,
                                node.nodeId(),
                                node.nodeName(),
                                Section.QUEUE,
                                q.name(),
                                "Exists with a different " + String.join(", ", differing)
                                        + ". An apply never reconfigures an existing queue; use the queue's own edit"
                                        + " action, which shows the whole configuration it would replace."));
                    }
                }
            }
        }

        void addressSettings() {
            Map<String, Object> base = base();
            for (AddressSettingDecl s : doc.addressSettings()) {
                Map<String, Object> declared = Values.normalise(s.values());
                Map<String, Object> before =
                        Values.sorted(node.addressSettings().getOrDefault(s.match(), Map.of()));
                boolean already =
                        declared.entrySet().stream().allMatch(e -> Values.same(e.getValue(), before.get(e.getKey())));
                boolean explicitEntry = !before.isEmpty() && !sameMap(before, base);
                Map<String, Object> after = new TreeMap<>(base);
                after.putAll(declared);
                Op op = explicitEntry ? Op.REPLACE : Op.ADD;
                String desc = already
                        ? "Address setting " + s.match() + " already matches"
                        : (op == Op.ADD ? "Add" : "Replace") + " address setting " + s.match() + " (" + declared.size()
                                + (declared.size() == 1 ? " key" : " keys") + ")";
                steps.add(new Step(
                        id(Section.ADDRESS_SETTING, s.match(), op),
                        op,
                        Section.ADDRESS_SETTING,
                        s.match(),
                        before,
                        after,
                        already,
                        desc));
                if (!already) {
                    addressSettingHazards(s, declared, before, after, base);
                }
            }
        }

        private void addressSettingHazards(
                AddressSettingDecl s,
                Map<String, Object> declared,
                Map<String, Object> before,
                Map<String, Object> after,
                Map<String, Object> base) {
            String match = s.match();
            List<String> covered = node.addresses().keySet().stream()
                    .filter(a -> AddressMatch.covers(match, a))
                    .sorted()
                    .toList();
            boolean coversSomething = !covered.isEmpty();

            if (AddressMatch.isCatchAll(match)) {
                hazard(
                        HazardKind.BROAD_MATCH,
                        HazardClass.HIGH,
                        Section.ADDRESS_SETTING,
                        match,
                        null,
                        "Match '" + match + "' is the catch-all: this replaces the default every address inherits.");
            }
            // Replace semantics (M2): every key set on this match that the declaration
            // does not set falls back to what the parent hierarchy gives.
            for (Map.Entry<String, Object> e : before.entrySet()) {
                if (declared.containsKey(e.getKey())) {
                    continue;
                }
                Object inherited = base.get(e.getKey());
                if (!Values.same(e.getValue(), inherited)) {
                    hazard(
                            HazardKind.UNINTENDED_KEY_CHANGE,
                            HazardClass.HIGH,
                            Section.ADDRESS_SETTING,
                            match,
                            e.getKey(),
                            xml(e.getKey()) + " is " + e.getValue()
                                    + " on this match and the declaration does not set it;"
                                    + " after the apply it reverts to "
                                    + (inherited == null ? "the broker's default" : inherited + " (inherited)")
                                    + ". Declare it to keep it.");
                }
            }
            Object policy = declared.get(AddressSettingKey.ADDRESS_FULL_MESSAGE_POLICY.jsonName());
            Object beforePolicy = before.get(AddressSettingKey.ADDRESS_FULL_MESSAGE_POLICY.jsonName());
            if (policy != null && !Values.same(policy, beforePolicy)) {
                String p = policy.toString();
                if (p.equals("DROP") || p.equals("FAIL")) {
                    hazard(
                            HazardKind.MESSAGE_LOSS_POLICY,
                            coversSomething ? HazardClass.HIGH : HazardClass.MEDIUM,
                            Section.ADDRESS_SETTING,
                            match,
                            AddressSettingKey.ADDRESS_FULL_MESSAGE_POLICY.jsonName(),
                            "address-full-policy becomes " + p + ": once the address is full, producers'"
                                    + (p.equals("DROP") ? " messages are discarded" : " sends are refused")
                                    + coveredText(covered) + ".");
                } else if (p.equals("BLOCK")) {
                    hazard(
                            HazardKind.BLOCKING_POLICY,
                            coversSomething ? HazardClass.HIGH : HazardClass.MEDIUM,
                            Section.ADDRESS_SETTING,
                            match,
                            AddressSettingKey.ADDRESS_FULL_MESSAGE_POLICY.jsonName(),
                            "address-full-policy becomes BLOCK: once the address is full, producers stall until it"
                                    + " drains" + coveredText(covered) + ".");
                }
            }
            Object pagePolicy = declared.get(AddressSettingKey.PAGE_FULL_MESSAGE_POLICY.jsonName());
            if (pagePolicy != null
                    && "FAIL".equals(pagePolicy.toString())
                    && !Values.same(pagePolicy, before.get(AddressSettingKey.PAGE_FULL_MESSAGE_POLICY.jsonName()))) {
                hazard(
                        HazardKind.MESSAGE_LOSS_POLICY,
                        coversSomething ? HazardClass.HIGH : HazardClass.MEDIUM,
                        Section.ADDRESS_SETTING,
                        match,
                        AddressSettingKey.PAGE_FULL_MESSAGE_POLICY.jsonName(),
                        "page-full-policy becomes FAIL: once the page limit is reached, sends are refused"
                                + coveredText(covered) + ".");
            }
            limitBelowUsage(match, declared, AddressSettingKey.MAX_SIZE_BYTES, covered, true);
            limitBelowUsage(match, declared, AddressSettingKey.PAGE_LIMIT_BYTES, covered, true);
            limitBelowUsage(match, declared, AddressSettingKey.MAX_SIZE_MESSAGES, covered, false);
            limitBelowUsage(match, declared, AddressSettingKey.PAGE_LIMIT_MESSAGES, covered, false);
            for (AddressSettingKey k :
                    List.of(AddressSettingKey.DEAD_LETTER_ADDRESS, AddressSettingKey.EXPIRY_ADDRESS)) {
                Object v = declared.get(k.jsonName());
                if (v != null && coversSomething && !Values.same(v, before.get(k.jsonName()))) {
                    hazard(
                            HazardKind.DLQ_EXPIRY_CHANGE,
                            HazardClass.MEDIUM,
                            Section.ADDRESS_SETTING,
                            match,
                            k.jsonName(),
                            k.xmlName() + " changes from " + orDefault(before.get(k.jsonName())) + " to " + v
                                    + coveredText(covered) + ".");
                }
            }
            for (AddressSettingKey k : List.of(
                    AddressSettingKey.AUTO_DELETE_QUEUES,
                    AddressSettingKey.AUTO_DELETE_ADDRESSES,
                    AddressSettingKey.AUTO_DELETE_CREATED_QUEUES)) {
                Object v = declared.get(k.jsonName());
                if (Boolean.TRUE.equals(v) && coversSomething && !Values.same(v, before.get(k.jsonName()))) {
                    hazard(
                            HazardKind.AUTO_DELETE_ENABLED,
                            HazardClass.MEDIUM,
                            Section.ADDRESS_SETTING,
                            match,
                            k.jsonName(),
                            k.xmlName() + " turns on: the broker will remove idle resources" + coveredText(covered)
                                    + ".");
                }
            }
            Object redistribution = declared.get(AddressSettingKey.REDISTRIBUTION_DELAY.jsonName());
            if (redistribution != null
                    && !Values.same(redistribution, before.get(AddressSettingKey.REDISTRIBUTION_DELAY.jsonName()))) {
                hazard(
                        HazardKind.REDISTRIBUTION_CHANGE,
                        HazardClass.LOW,
                        Section.ADDRESS_SETTING,
                        match,
                        AddressSettingKey.REDISTRIBUTION_DELAY.jsonName(),
                        "redistribution-delay changes from "
                                + orDefault(before.get(AddressSettingKey.REDISTRIBUTION_DELAY.jsonName())) + " to "
                                + redistribution + "; -1 stops messages moving to nodes with consumers.");
            }
        }

        private void limitBelowUsage(
                String match,
                Map<String, Object> declared,
                AddressSettingKey key,
                List<String> covered,
                boolean bytes) {
            BigDecimal limit = BrokerConfigValidator.number(declared.get(key.jsonName()));
            if (limit == null || limit.signum() < 0) {
                return;
            }
            for (String address : covered) {
                AddressUsage usage = node.addressUsage().get(address);
                if (usage == null) {
                    continue;
                }
                long current = bytes ? usage.bytes() : usage.messages();
                if (BigDecimal.valueOf(current).compareTo(limit) > 0) {
                    hazard(
                            HazardKind.LIMIT_BELOW_USAGE,
                            HazardClass.HIGH,
                            Section.ADDRESS_SETTING,
                            match,
                            key.jsonName() + ":" + address,
                            key.xmlName() + " = " + limit + " is below what " + address + " already holds on "
                                    + node.nodeName() + " (" + current + (bytes ? " bytes" : " messages")
                                    + "); the full-address policy triggers immediately.");
                }
            }
        }

        void securitySettings() {
            Map<PermissionType, Set<String>> baseRoles = node.securitySettings().getOrDefault(DEFAULT_MATCH, Map.of());
            for (SecuritySettingDecl s : doc.securitySettings()) {
                Map<PermissionType, Set<String>> observed =
                        node.securitySettings().get(s.match());
                boolean already = observed != null && sameRoles(observed, s.permissions());
                boolean explicitEntry = observed != null && !observed.isEmpty() && !sameRoles(observed, baseRoles);
                Op op = explicitEntry ? Op.REPLACE : Op.ADD;
                steps.add(new Step(
                        id(Section.SECURITY_SETTING, s.match(), op),
                        op,
                        Section.SECURITY_SETTING,
                        s.match(),
                        rolesMap(observed),
                        rolesMap(s.permissions()),
                        already,
                        already
                                ? "Security setting " + s.match() + " already matches"
                                : (op == Op.ADD ? "Add" : "Replace") + " security setting " + s.match()));
                if (already) {
                    continue;
                }
                if (AddressMatch.isCatchAll(s.match())) {
                    hazard(
                            HazardKind.BROAD_MATCH,
                            HazardClass.HIGH,
                            Section.SECURITY_SETTING,
                            s.match(),
                            null,
                            "Match '" + s.match() + "' is the catch-all: every address without a more specific"
                                    + " security setting gets exactly these roles.");
                }
                if (PROTECTED_ADDRESSES.stream().anyMatch(a -> AddressMatch.covers(s.match(), a))) {
                    hazard(
                            HazardKind.MANAGEMENT_ACCESS,
                            HazardClass.HIGH,
                            Section.SECURITY_SETTING,
                            s.match(),
                            null,
                            "Match '" + s.match() + "' covers the broker's management or notification address. If"
                                    + " Studio's own user loses manage or view here, Studio is locked out and cannot"
                                    + " revert it; the canary's verification read must succeed or the run halts.");
                }
            }
        }

        void diverts() {
            for (DivertDecl d : doc.diverts()) {
                DivertDecl observed = node.diverts().get(d.name());
                Map<String, Object> after = divertMap(d);
                if (observed == null) {
                    steps.add(new Step(
                            id(Section.DIVERT, d.name(), Op.ADD),
                            Op.ADD,
                            Section.DIVERT,
                            d.name(),
                            Map.of(),
                            after,
                            false,
                            "Create divert " + d.name() + " from " + d.address() + " to " + d.forwardingAddress()));
                    divertHazards(d);
                } else if (observed.sameAs(d)) {
                    steps.add(new Step(
                            id(Section.DIVERT, d.name(), Op.ADD),
                            Op.ADD,
                            Section.DIVERT,
                            d.name(),
                            divertMap(observed),
                            after,
                            true,
                            "Divert " + d.name() + " exists as declared"));
                } else {
                    // A second createDivert for an existing name succeeds and changes
                    // nothing (M4), so a change is an explicit remove and add.
                    steps.add(new Step(
                            id(Section.DIVERT, d.name(), Op.REMOVE),
                            Op.REMOVE,
                            Section.DIVERT,
                            d.name(),
                            divertMap(observed),
                            Map.of(),
                            false,
                            "Remove divert " + d.name() + " (it differs from the declaration)"));
                    steps.add(new Step(
                            id(Section.DIVERT, d.name(), Op.ADD),
                            Op.ADD,
                            Section.DIVERT,
                            d.name(),
                            Map.of(),
                            after,
                            false,
                            "Recreate divert " + d.name() + " as declared"));
                    hazard(
                            HazardKind.DIVERT_REPLACE,
                            HazardClass.MEDIUM,
                            Section.DIVERT,
                            d.name(),
                            null,
                            "Divert " + d.name() + " exists with different properties. Changing it is a delete and a"
                                    + " create; messages arriving between the two are not diverted.");
                    divertHazards(d);
                }
            }
        }

        private void divertHazards(DivertDecl d) {
            if (!d.exclusive()) {
                return;
            }
            boolean hasQueues =
                    node.queues().values().stream().anyMatch(q -> d.address().equals(q.get("address")));
            hazard(
                    HazardKind.EXCLUSIVE_DIVERT,
                    hasQueues ? HazardClass.HIGH : HazardClass.MEDIUM,
                    Section.DIVERT,
                    d.name(),
                    null,
                    "Divert " + d.name() + " is exclusive: messages on " + d.address() + " are taken to "
                            + d.forwardingAddress() + ", not copied"
                            + (hasQueues ? "; queues on " + d.address() + " stop receiving them" : "") + ".");
        }

        void removals() {
            Set<String> declaredMatches = new HashSet<>();
            doc.addressSettings().forEach(s -> declaredMatches.add(s.match()));
            Set<String> declaredSecurity = new HashSet<>();
            doc.securitySettings().forEach(s -> declaredSecurity.add(s.match()));
            Set<String> declaredDiverts = new HashSet<>();
            doc.diverts().forEach(d -> declaredDiverts.add(d.name()));
            Set<String> ownedDiverts = new HashSet<>();

            // Reverse dependency order: diverts, then security settings, then address settings.
            for (OwnedItem item : owned.stream()
                    .filter(o -> o.section() == Section.DIVERT)
                    .sorted(Comparator.comparing(OwnedItem::key))
                    .toList()) {
                ownedDiverts.add(item.key());
                DivertDecl observed = node.diverts().get(item.key());
                if (declaredDiverts.contains(item.key()) || observed == null) {
                    continue;
                }
                steps.add(new Step(
                        id(Section.DIVERT, item.key(), Op.REMOVE),
                        Op.REMOVE,
                        Section.DIVERT,
                        item.key(),
                        divertMap(observed),
                        Map.of(),
                        false,
                        "Remove divert " + item.key() + " (Studio applied it; it is no longer declared)"));
                hazard(
                        HazardKind.REMOVE_OWNED,
                        HazardClass.MEDIUM,
                        Section.DIVERT,
                        item.key(),
                        null,
                        "Divert " + item.key() + " is removed because it left the declaration.");
            }
            if (options.removeUndeclared()) {
                for (Map.Entry<String, DivertDecl> e : new TreeMap<>(node.diverts()).entrySet()) {
                    if (declaredDiverts.contains(e.getKey()) || ownedDiverts.contains(e.getKey())) {
                        continue;
                    }
                    steps.add(new Step(
                            id(Section.DIVERT, e.getKey(), Op.REMOVE),
                            Op.REMOVE,
                            Section.DIVERT,
                            e.getKey(),
                            divertMap(e.getValue()),
                            Map.of(),
                            false,
                            "Remove divert " + e.getKey() + " (not declared; Studio did not create it)"));
                    hazard(
                            HazardKind.REMOVE_UNDECLARED,
                            HazardClass.HIGH,
                            Section.DIVERT,
                            e.getKey(),
                            null,
                            "Divert " + e.getKey() + " was not created by Studio. If broker.xml declares it, it"
                                    + " comes back on the next restart and Studio cannot tell.");
                }
            }
            Map<PermissionType, Set<String>> baseRoles = node.securitySettings().getOrDefault(DEFAULT_MATCH, Map.of());
            for (OwnedItem item : owned.stream()
                    .filter(o -> o.section() == Section.SECURITY_SETTING)
                    .sorted(Comparator.comparing(OwnedItem::key))
                    .toList()) {
                Map<PermissionType, Set<String>> observed =
                        node.securitySettings().get(item.key());
                if (declaredSecurity.contains(item.key()) || observed == null || sameRoles(observed, baseRoles)) {
                    continue;
                }
                steps.add(new Step(
                        id(Section.SECURITY_SETTING, item.key(), Op.REMOVE),
                        Op.REMOVE,
                        Section.SECURITY_SETTING,
                        item.key(),
                        rolesMap(observed),
                        Map.of(),
                        false,
                        "Remove security setting " + item.key() + " (Studio applied it; it is no longer declared)"));
                hazard(
                        HazardKind.REMOVE_OWNED,
                        HazardClass.MEDIUM,
                        Section.SECURITY_SETTING,
                        item.key(),
                        null,
                        "Security setting " + item.key() + " is removed; addresses under it fall back to the next"
                                + " match.");
            }
            Map<String, Object> base = base();
            for (OwnedItem item : owned.stream()
                    .filter(o -> o.section() == Section.ADDRESS_SETTING)
                    .sorted(Comparator.comparing(OwnedItem::key))
                    .toList()) {
                Map<String, Object> observed = node.addressSettings().get(item.key());
                if (declaredMatches.contains(item.key()) || observed == null || sameMap(observed, base)) {
                    continue;
                }
                steps.add(new Step(
                        id(Section.ADDRESS_SETTING, item.key(), Op.REMOVE),
                        Op.REMOVE,
                        Section.ADDRESS_SETTING,
                        item.key(),
                        Values.sorted(observed),
                        Values.sorted(base),
                        false,
                        "Remove address setting " + item.key() + " (Studio applied it; it is no longer declared)"));
                hazard(
                        HazardKind.REMOVE_OWNED,
                        HazardClass.MEDIUM,
                        Section.ADDRESS_SETTING,
                        item.key(),
                        null,
                        "Address setting " + item.key() + " is removed; addresses under it inherit the parent"
                                + " match's values.");
            }
        }

        void undeclared() {
            if (!options.reportUndeclared()) {
                return;
            }
            Set<String> declaredAddresses = new HashSet<>();
            Set<String> declaredQueues = new HashSet<>();
            for (AddressDecl a : doc.addresses()) {
                declaredAddresses.add(a.name());
                a.queues().forEach(q -> declaredQueues.add(q.name()));
            }
            Set<String> declaredDiverts = new HashSet<>();
            doc.diverts().forEach(d -> declaredDiverts.add(d.name()));
            for (String address : new TreeMap<>(node.addresses()).keySet()) {
                if (!declaredAddresses.contains(address) && !excluded(address) && !system(address)) {
                    findings.add(new Finding(
                            FindingKind.UNDECLARED,
                            node.nodeId(),
                            node.nodeName(),
                            Section.ADDRESS,
                            address,
                            "Exists and is not declared."));
                }
            }
            for (Map.Entry<String, Map<String, Object>> e : new TreeMap<>(node.queues()).entrySet()) {
                String address = Objects.toString(e.getValue().get("address"), "");
                if (!declaredQueues.contains(e.getKey())
                        && !excluded(e.getKey())
                        && !excluded(address)
                        && !system(address)
                        && !system(e.getKey())) {
                    findings.add(new Finding(
                            FindingKind.UNDECLARED,
                            node.nodeId(),
                            node.nodeName(),
                            Section.QUEUE,
                            e.getKey(),
                            address.isEmpty()
                                    ? "Exists and is not declared."
                                    : "Exists on " + address + " and is not declared."));
                }
            }
            Set<String> removing = new HashSet<>();
            steps.stream()
                    .filter(s -> s.section() == Section.DIVERT && s.op() == Op.REMOVE)
                    .forEach(s -> removing.add(s.key()));
            for (String divert : new TreeMap<>(node.diverts()).keySet()) {
                if (!declaredDiverts.contains(divert) && !excluded(divert) && !removing.contains(divert)) {
                    findings.add(new Finding(
                            FindingKind.UNDECLARED,
                            node.nodeId(),
                            node.nodeName(),
                            Section.DIVERT,
                            divert,
                            "Exists and is not declared."));
                }
            }
        }

        private boolean excluded(String name) {
            return options.undeclaredExclusions().stream().anyMatch(p -> AddressMatch.covers(p, name));
        }

        private static boolean system(String address) {
            return address.startsWith("activemq.") || address.startsWith("$sys.") || address.startsWith("$.artemis.");
        }

        private void hazard(
                HazardKind kind, HazardClass hazardClass, Section section, String key, String subKey, String message) {
            String id = kind + ":" + node.nodeId() + ":" + section + ":" + key + (subKey == null ? "" : ":" + subKey);
            hazards.add(new Hazard(id, kind, hazardClass, node.nodeId(), node.nodeName(), section, key, message));
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static String id(Section section, String key, Op op) {
        return section + ":" + key + ":" + op;
    }

    private static String xml(String jsonName) {
        return AddressSettingKey.byJsonName(jsonName)
                .map(AddressSettingKey::xmlName)
                .orElse(jsonName);
    }

    private static String orDefault(Object value) {
        return value == null ? "the broker's default" : value.toString();
    }

    private static String coveredText(List<String> covered) {
        if (covered.isEmpty()) {
            return " (no address on this node is under the match yet)";
        }
        String shown = covered.size() <= 3
                ? String.join(", ", covered)
                : String.join(", ", covered.subList(0, 3)) + " and " + (covered.size() - 3) + " more";
        return " — under the match on this node: " + shown;
    }

    private static boolean sameMap(Map<String, Object> a, Map<String, Object> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (!b.containsKey(e.getKey()) || !Values.same(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Equal on every type the broker reports back; the unechoed ones cannot differ observably. */
    private static boolean sameRoles(Map<PermissionType, Set<String>> a, Map<PermissionType, Set<String>> b) {
        for (PermissionType t : PermissionType.values()) {
            if (t.echoed() && !a.getOrDefault(t, Set.of()).equals(b.getOrDefault(t, Set.of()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> rolesMap(Map<PermissionType, Set<String>> roles) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (roles == null) {
            return out;
        }
        for (PermissionType t : PermissionType.values()) {
            Set<String> r = roles.get(t);
            if (r != null && !r.isEmpty()) {
                out.put(t.xmlName(), String.join(",", new java.util.TreeSet<>(r)));
            }
        }
        return out;
    }

    /** A {@code DivertConfiguration} document with the hyphenated keys the JSON arm expects. */
    public static Map<String, Object> divertMap(DivertDecl d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("routing-name", d.name());
        m.put("address", d.address());
        m.put("forwarding-address", d.forwardingAddress());
        m.put("exclusive", d.exclusive());
        if (d.filter() != null) {
            m.put("filter-string", d.filter());
        }
        if (d.routingType() != null) {
            m.put("routing-type", d.routingType());
        }
        if (d.transformerClassName() != null) {
            m.put("transformer-class-name", d.transformerClassName());
            if (!d.transformerProperties().isEmpty()) {
                m.put("transformer-properties", d.transformerProperties());
            }
        }
        return m;
    }

    /** A {@code QueueConfiguration} document, on the shape {@code QueueLifecycleService} sends. */
    public static Map<String, Object> queueConfig(String address, QueueDecl q) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", q.name());
        config.put("address", address);
        config.put("routing-type", q.routingType().toUpperCase(Locale.ROOT));
        config.put("durable", q.durable());
        config.put("auto-create-address", false);
        if (q.filter() != null) {
            config.put("filter-string", q.filter());
        }
        if (q.maxConsumers() != null) {
            config.put("max-consumers", q.maxConsumers());
        }
        if (q.purgeOnNoConsumers() != null) {
            config.put("purge-on-no-consumers", q.purgeOnNoConsumers());
        }
        if (q.exclusive() != null) {
            config.put("exclusive", q.exclusive());
        }
        if (q.nonDestructive() != null) {
            config.put("non-destructive", q.nonDestructive());
        }
        if (q.ringSize() != null) {
            config.put("ring-size", q.ringSize());
        }
        return config;
    }

    private static UUID canary(List<NodePlan> nodes, UUID requested) {
        List<NodePlan> readable = nodes.stream().filter(NodePlan::readable).toList();
        if (requested != null && readable.stream().anyMatch(n -> n.nodeId().equals(requested))) {
            return requested;
        }
        return readable.isEmpty() ? null : readable.getFirst().nodeId();
    }

    private static String hash(List<NodePlan> nodes) {
        StringBuilder sb = new StringBuilder();
        for (NodePlan n : nodes) {
            for (Step s : n.steps()) {
                if (s.already()) {
                    continue;
                }
                sb.append(n.nodeId())
                        .append('|')
                        .append(s.id())
                        .append('|')
                        .append(new TreeMap<>(s.after()))
                        .append('\n');
            }
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

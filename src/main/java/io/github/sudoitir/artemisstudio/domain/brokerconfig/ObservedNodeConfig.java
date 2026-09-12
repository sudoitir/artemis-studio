package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.DivertDecl;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What one batched read of one node returned — the only input the planner compares
 * a declaration against. Address settings and security settings are keyed by the
 * probe string they were read at (the declared match itself, plus {@code #}), and
 * hold the broker's <em>merged</em> answer for it: there is no operation that returns
 * a literal entry ({@code docs/broker-management-notes.md} §15 M5).
 *
 * <p>{@code unavailableReason} is non-null when the read failed; every map is then
 * empty and the planner reports the node as unreachable rather than as missing
 * everything.
 */
public record ObservedNodeConfig(
        UUID nodeId,
        String nodeName,
        boolean live,
        Map<String, Set<String>> addresses,
        Map<String, Map<String, Object>> queues,
        Map<String, Map<String, Object>> addressSettings,
        Map<String, Map<PermissionType, Set<String>>> securitySettings,
        Map<String, DivertDecl> diverts,
        Map<String, AddressUsage> addressUsage,
        String unavailableReason) {

    public ObservedNodeConfig {
        addresses = addresses == null ? Map.of() : Map.copyOf(addresses);
        queues = queues == null ? Map.of() : Map.copyOf(queues);
        addressSettings = addressSettings == null ? Map.of() : Map.copyOf(addressSettings);
        securitySettings = securitySettings == null ? Map.of() : Map.copyOf(securitySettings);
        diverts = diverts == null ? Map.of() : Map.copyOf(diverts);
        addressUsage = addressUsage == null ? Map.of() : Map.copyOf(addressUsage);
    }

    /** A node that was not live, and therefore not read. */
    public static ObservedNodeConfig notLive(UUID nodeId, String nodeName) {
        return new ObservedNodeConfig(
                nodeId, nodeName, false, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    /** A live node whose read failed, with the classified reason. */
    public static ObservedNodeConfig unreachable(UUID nodeId, String nodeName, String reason) {
        return new ObservedNodeConfig(
                nodeId, nodeName, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), reason);
    }

    public boolean readable() {
        return live && unavailableReason == null;
    }

    /** What an address currently holds, for the limit-below-usage hazard. */
    public record AddressUsage(long bytes, long messages) {}
}

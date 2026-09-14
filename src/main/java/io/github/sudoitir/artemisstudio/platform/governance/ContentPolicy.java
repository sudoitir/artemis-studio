package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The content policy (ADR-0075). Every path that serialises or stores message content passes it
 * through here first and handles only the {@link GovernedMessage} that comes back.
 */
@Component
@RequiredArgsConstructor
public class ContentPolicy {

    private final PolicyStore store;
    private final PermissionResolver permissions;
    private final SettingsService settings;
    private final FindingsRecorder findings;
    private final ObjectMapper mapper;

    /** The caller's context for one cluster and address: whether they hold {@code message:clear} there. */
    public GovernContext context(UUID clusterId, String address) {
        return new GovernContext(clusterId, address, permissions.can(clusterId, GovernancePermissions.MESSAGE_CLEAR));
    }

    /** Govern a message for the caller described by {@code context}. */
    public GovernedMessage govern(GovernContext context, MessageContent content) {
        return engine().govern(context, content, false);
    }

    /** Govern a message for storage: masked for everyone, with sealable originals collected. */
    public GovernedMessage governForStorage(UUID clusterId, String address, MessageContent content) {
        return engine().govern(GovernContext.masked(clusterId, address), content, true);
    }

    /** Mask what the detectors find in free text. No rules, no clear access. */
    public String governText(String text) {
        return engine().governText(text);
    }

    /** Whether any masking rule names this header or property on some address. */
    public boolean classifies(Location location, String name) {
        return store.current().classifiesAnywhere(location, name);
    }

    public int version() {
        return store.current().version();
    }

    private PolicyEngine engine() {
        return new PolicyEngine(
                store.current(), settings.intValue(GovernanceSettings.SCAN_LIMIT), mapper, findings::record);
    }
}

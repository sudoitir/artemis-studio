package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.governance.ContentSealer;
import io.github.sudoitir.artemisstudio.platform.governance.GovernContext;
import io.github.sudoitir.artemisstudio.platform.governance.GovernanceViews;
import io.github.sudoitir.artemisstudio.platform.governance.GovernedMessage;
import io.github.sudoitir.artemisstudio.platform.governance.MessageContent;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A captured request or reply payload, governed where it is stored and where it is read (request-reply-tracing
 * spec). The stored {@code rr_event.detail} holds the masked preview, the policy version it was masked under,
 * and its sealed originals; a read never returns the ciphertext.
 */
@Component
@RequiredArgsConstructor
class RrPayloads {

    static final String BODY_PREVIEW = "bodyPreview";
    private static final String TRUNCATED = "truncated";
    private static final String POLICY_VERSION = "policyVersion";
    private static final String SEALED = "sealed";
    private static final String NONCE = "nonce";

    private final ContentPolicy policy;
    private final ContentSealer sealer;

    /** A payload as one caller may see it, and the governed message behind it for the clear-view audit. */
    record Viewed(Map<String, Object> detail, GovernedMessage governed) {}

    /** The AAD an event's originals are sealed under: its flow, kind and time, which the row keeps unchanged. */
    static String aad(UUID flowId, String kind, Instant at) {
        return "governance:rr_event:" + flowId + ":" + kind + ":" + at.toEpochMilli();
    }

    /** The detail to store for a freshly captured preview. */
    Map<String, Object> stored(
            UUID clusterId, String address, UUID flowId, String kind, Instant at, String preview, boolean truncated) {
        return storedFrom(clusterId, address, flowId, kind, at, preview(preview), truncated);
    }

    /** The detail to store again under the current policy, from a stored detail and its sealed originals. */
    Map<String, Object> remasked(
            UUID clusterId, String address, UUID flowId, String kind, Instant at, Map<String, Object> stored) {
        MessageContent restored =
                ContentSealer.restore(preview(text(stored.get(BODY_PREVIEW))), originals(flowId, kind, at, stored));
        return storedFrom(clusterId, address, flowId, kind, at, restored, Boolean.TRUE.equals(stored.get(TRUNCATED)));
    }

    /** The detail one caller reads: masked, or unsealed and marked as sensitive for a caller with clear access. */
    Viewed view(
            UUID clusterId,
            String address,
            UUID flowId,
            String kind,
            Instant at,
            Map<String, Object> stored,
            boolean clearAccess) {
        MessageContent content = preview(text(stored.get(BODY_PREVIEW)));
        if (clearAccess) {
            content = ContentSealer.restore(content, originals(flowId, kind, at, stored));
        }
        GovernedMessage governed = policy.govern(new GovernContext(clusterId, address, clearAccess), content);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(BODY_PREVIEW, governed.body());
        if (Boolean.TRUE.equals(stored.get(TRUNCATED))) {
            detail.put(TRUNCATED, true);
        }
        detail.put("redactions", GovernanceViews.redactions(governed));
        if (!governed.withheld().isEmpty()) {
            detail.put("withheld", GovernanceViews.withheld(governed));
        }
        return new Viewed(detail, governed);
    }

    private Map<String, Object> storedFrom(
            UUID clusterId,
            String address,
            UUID flowId,
            String kind,
            Instant at,
            MessageContent content,
            boolean truncated) {
        GovernedMessage governed = policy.governForStorage(clusterId, address, content);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(BODY_PREVIEW, governed.body());
        if (truncated) {
            detail.put(TRUNCATED, true);
        }
        detail.put(POLICY_VERSION, governed.policyVersion());
        ContentSealer.SealedOriginals sealed = sealer.seal(aad(flowId, kind, at), governed.sealable());
        if (sealed != null) {
            detail.put(SEALED, Base64.getEncoder().encodeToString(sealed.ciphertext()));
            detail.put(NONCE, Base64.getEncoder().encodeToString(sealed.nonce()));
        }
        return detail;
    }

    private Map<String, String> originals(UUID flowId, String kind, Instant at, Map<String, Object> stored) {
        if (!(stored.get(SEALED) instanceof String sealed) || !(stored.get(NONCE) instanceof String nonce)) {
            return Map.of();
        }
        try {
            return sealer.unseal(
                    aad(flowId, kind, at),
                    Base64.getDecoder().decode(sealed),
                    Base64.getDecoder().decode(nonce));
        } catch (IllegalArgumentException notBase64) {
            return Map.of();
        }
    }

    private static MessageContent preview(String preview) {
        return new MessageContent(Map.of(), Map.of(), preview, false, null);
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }
}

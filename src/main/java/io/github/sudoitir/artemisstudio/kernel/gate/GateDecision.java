package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Duration;
import java.util.Objects;

/** An {@link ApprovalProvider}'s answer. */
@PluginApi
public sealed interface GateDecision {

    /** Run it now; the policy is recorded on the operation's audit row. */
    record Allow(PolicyRef policy) implements GateDecision {}

    /**
     * Hold it for approval.
     *
     * @param ttl how long it may wait, from 1 minute to Studio's {@code gate.max-hold} (30 days by
     *     default); a duration, because only the database clock counts
     * @param reasonRequired whether the requester must give a reason
     * @param approverHint who should decide, in words, such as "a platform lead"; or {@code null}
     */
    record Hold(PolicyRef policy, Duration ttl, boolean reasonRequired, String approverHint) implements GateDecision {

        public Hold {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(ttl, "ttl");
        }
    }

    /** Refuse it, with a reason the requester sees. */
    record Deny(String reason) implements GateDecision {

        public Deny {
            Objects.requireNonNull(reason, "reason");
        }
    }
}

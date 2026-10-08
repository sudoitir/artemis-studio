package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Objects;

/**
 * A provider's answer to {@link ApprovalProvider#checkVote}.
 *
 * @param reason why the vote is refused, shown to the approver; {@code null} when allowed
 */
@PluginApi
public record VoteCheck(boolean allowed, String reason) {

    public static VoteCheck allow() {
        return new VoteCheck(true, null);
    }

    public static VoteCheck refuse(String reason) {
        return new VoteCheck(false, Objects.requireNonNull(reason, "reason"));
    }
}

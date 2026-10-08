package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Objects;

/**
 * A provider's answer to {@link ApprovalProvider#checkRun}.
 *
 * @param reason why the run is refused, shown to the requester; {@code null} when allowed
 */
@PluginApi
public record RunCheck(boolean allowed, String reason) {

    public static RunCheck allow() {
        return new RunCheck(true, null);
    }

    public static RunCheck refuse(String reason) {
        return new RunCheck(false, Objects.requireNonNull(reason, "reason"));
    }
}

package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.feature.messages.MessagePermissions;

/** Whether the messages leave the source. */
public enum TransferMode {
    /** The messages leave the source queue. */
    MOVE(MessagePermissions.MESSAGE_MOVE),
    /** The source queue is left as it is. */
    COPY(MessagePermissions.MESSAGE_READ);

    private final String sourcePermission;

    TransferMode(String sourcePermission) {
        this.sourcePermission = sourcePermission;
    }

    /** What the operator needs on the source cluster; the target always needs {@code message:send}. */
    public String sourcePermission() {
        return sourcePermission;
    }
}

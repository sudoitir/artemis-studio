package io.github.sudoitir.artemisstudio.kernel.gate;

/**
 * An access change would leave an enforcing approval gate with fewer approvers than it needs, so nothing could be
 * approved by anyone but the requester. Mapped to HTTP 409 {@code approver-quorum}; the change was not made.
 */
public class ApproverQuorumException extends RuntimeException {

    public ApproverQuorumException(String message) {
        super(message);
    }
}

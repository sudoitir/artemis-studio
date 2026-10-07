package io.github.sudoitir.artemisstudio.kernel.approval;

/** The requester already has {@code gate.max-open-per-requester} requests open. Mapped to HTTP 429. */
public class TooManyHeldOperationsException extends RuntimeException {

    public TooManyHeldOperationsException(int open) {
        super("You already have " + open + " requests waiting for approval or running. Cancel one, or wait for them to"
                + " be decided, then try again.");
    }
}

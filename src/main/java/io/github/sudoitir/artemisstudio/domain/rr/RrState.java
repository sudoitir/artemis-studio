package io.github.sudoitir.artemisstudio.domain.rr;

/** The six states {@code rr_flow}'s CHECK constraint enumerates (007-request-reply.sql). */
public enum RrState {
    AWAITING_REPLY,
    COMPLETED,
    TIMED_OUT,
    ORPHANED,
    RESPONDER_DROPPED,
    ORPHANED_REPLY
}

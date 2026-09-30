package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * A signed-in session of the same user was given a new id on this instance (a step-up, enrolling a
 * factor, a password change), which is what the session's id is rotated for. Long-lived responses tied
 * to the session, such as event streams, follow it to {@code newId} instead of ending with the old id.
 * A new sign-in is not this: whoever signs in next does not inherit what an earlier user opened.
 */
public record SessionIdChanged(String oldId, String newId) {}

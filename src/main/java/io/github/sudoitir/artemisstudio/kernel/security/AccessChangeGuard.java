package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * A check an access change must pass before its transaction commits. A guard throws to refuse the change: the
 * transaction is rolled back and nothing was changed. It runs once per transaction that announced a change, with the
 * change's own writes visible to it. See {@link AccessChanges}.
 */
public interface AccessChangeGuard {

    void beforeCommit();
}

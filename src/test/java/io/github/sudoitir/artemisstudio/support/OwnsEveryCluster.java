package io.github.sudoitir.artemisstudio.support;

import io.github.sudoitir.artemisstudio.platform.clusters.ClusterOwnership;
import org.mockito.Answers;
import org.mockito.Mockito;

/**
 * The {@link ClusterOwnership} of a test context that owns every cluster. Every cached context is a replica
 * of the same database, so real ownership would spread a test's cluster over contexts that are not running
 * the test; {@code ClusterOwnershipTest} builds real ones.
 *
 * <p>The answer is fixed when the mock is made, not stubbed before each test: the event bus may call the
 * mock while a test is stubbing it, and Mockito then attaches the stub to that other call and fails with
 * {@code CannotStubVoidMethodWithReturnValue}.
 */
final class OwnsEveryCluster {

    private OwnsEveryCluster() {}

    static ClusterOwnership clusterOwnership() {
        return Mockito.mock(
                ClusterOwnership.class,
                Mockito.withSettings()
                        .defaultAnswer(invocation ->
                                "owns".equals(invocation.getMethod().getName())
                                        ? Boolean.TRUE
                                        : Answers.RETURNS_DEFAULTS.answer(invocation)));
    }
}

package io.github.sudoitir.artemisstudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The scope walk and wildcard matching behind {@code @perm.can(...)} (authorization spec, ADR-0038). */
@ExtendWith(MockitoExtension.class)
class PermissionResolverTest {

    @Mock
    ClusterEnvironmentIndex environments;

    PermissionResolver resolver;

    UUID clusterId = UUID.randomUUID();
    UUID otherClusterId = UUID.randomUUID();
    UUID environmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new PermissionResolver(environments);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Set<Grant> grants) {
        StudioPrincipal principal = new StudioPrincipal(UUID.randomUUID(), "u", grants, false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @Test
    void noAuthenticationDeniesEverything() {
        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isFalse();
    }

    @Test
    void globalGrantCoversAnyCluster() {
        authenticateAs(Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(Permissions.CLUSTER_READ))));
        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(clusterId, Permissions.CLUSTER_WRITE)).isFalse();
    }

    @Test
    void environmentGrantCoversOnlyItsMembers() {
        when(environments.environmentOf(clusterId)).thenReturn(environmentId);
        when(environments.environmentOf(otherClusterId)).thenReturn(null);
        authenticateAs(Set.of(new Grant(Grant.ScopeType.ENVIRONMENT, environmentId, Set.of(Permissions.CLUSTER_READ))));

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isFalse();
    }

    @Test
    void clusterGrantDoesNotLeakToASiblingClusterInTheSameEnvironment() {
        when(environments.environmentOf(clusterId)).thenReturn(environmentId);
        when(environments.environmentOf(otherClusterId)).thenReturn(environmentId);
        authenticateAs(Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.CLUSTER_READ))));

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isFalse();
    }

    @Test
    void wildcardGrantsEveryPermission() {
        authenticateAs(Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(Permissions.WILDCARD))));
        assertThat(resolver.can(clusterId, Permissions.QUEUE_PURGE)).isTrue();
        assertThat(resolver.can(Permissions.USER_ADMIN)).isTrue();
    }

    @Test
    void resourceWildcardGrantsEveryVerbOnThatResource() {
        authenticateAs(Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("message:*"))));
        assertThat(resolver.can(clusterId, Permissions.MESSAGE_SEND)).isTrue();
        assertThat(resolver.can(clusterId, Permissions.MESSAGE_DELETE)).isTrue();
        assertThat(resolver.can(clusterId, Permissions.QUEUE_PURGE)).isFalse();
    }

    @Test
    void grantsUnionAcrossMultipleRoles() {
        authenticateAs(Set.of(
                new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.CLUSTER_READ)),
                new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.MESSAGE_SEND))));
        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(clusterId, Permissions.MESSAGE_SEND)).isTrue();
        assertThat(resolver.can(clusterId, Permissions.QUEUE_PURGE)).isFalse();
    }
}

package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.app.StudioFeatures;
import io.github.sudoitir.artemisstudio.feature.alerting.AlertPermissions;
import io.github.sudoitir.artemisstudio.feature.messages.MessagePermissions;
import io.github.sudoitir.artemisstudio.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamShareEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamShareRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterPermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The decision every permission check goes through: the scope walk and wildcards of role grants, what a
 * permission's catalogue scope allows, and the team and share paths of a resource check (authorization and
 * team-access specs, ADR-0038). The catalogue is the real core one; the database is stubbed.
 */
class PermissionResolverTest {

    static final String QUEUE_PURGE = MessagePermissions.QUEUE_PURGE;
    static final String QUEUE_READ = Permissions.QUEUE_READ;
    static final String MESSAGE_SEND = MessagePermissions.MESSAGE_SEND;
    static final String QUEUE_CREATE = "queue:create";

    static final Set<String> TEAM_VIEWER =
            Set.of(QUEUE_READ, Permissions.ADDRESS_READ, MessagePermissions.MESSAGE_READ);
    static final Set<String> TEAM_OPERATOR = Set.of(
            QUEUE_READ,
            Permissions.ADDRESS_READ,
            MessagePermissions.MESSAGE_READ,
            QUEUE_PURGE,
            MESSAGE_SEND,
            MessagePermissions.MESSAGE_DELETE);

    final ScopeHierarchy environments = mock(ScopeHierarchy.class);
    final AccessLoader access = mock(AccessLoader.class);
    final FeatureRegistry features = mock(FeatureRegistry.class);
    final TeamPatternRepository patterns = mock(TeamPatternRepository.class);
    final TeamShareRepository shares = mock(TeamShareRepository.class);
    final RolePermissionRepository rolePermissions = mock(RolePermissionRepository.class);

    final List<TeamPatternEntity> ownedRows = new ArrayList<>();
    final List<TeamShareEntity> shareRows = new ArrayList<>();
    final Map<UUID, Set<String>> rolePermissionRows = new HashMap<>();

    final UUID clusterId = UUID.randomUUID();
    final UUID otherClusterId = UUID.randomUUID();
    final UUID environmentId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final UUID orders = UUID.randomUUID();
    final UUID billing = UUID.randomUUID();

    PermissionResolver resolver;
    Map<String, CatalogueEntry> catalogue;

    @BeforeEach
    void setUp() {
        catalogue = StudioFeatures.descriptors().stream()
                .flatMap(d -> d.permissions().stream()
                        .map(p -> new CatalogueEntry(
                                p.action(), p.label(), d.id(), d.title(), p.scope(), p.resourceKinds(), p.requires())))
                .collect(Collectors.toMap(CatalogueEntry::action, e -> e));
        when(features.permission(anyString()))
                .thenAnswer(i -> Optional.ofNullable(catalogue.get(i.<String>getArgument(0))));
        when(features.catalogue()).thenAnswer(i -> List.copyOf(catalogue.values()));
        when(patterns.findAll()).thenAnswer(i -> List.copyOf(ownedRows));
        when(shares.findAll()).thenAnswer(i -> List.copyOf(shareRows));
        when(rolePermissions.findByIdRoleId(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> rolePermissionRows.getOrDefault(i.<UUID>getArgument(0), Set.of()).stream()
                        .map(action -> new RolePermissionEntity(i.getArgument(0), action))
                        .toList());
        resolver = new PermissionResolver(
                environments, access, new TeamIndex(patterns, shares, rolePermissions), features);
        access(Set.of(), Map.of());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** The signed-in user's access: their role grants and the permissions their team roles give them. */
    private void access(Set<Grant> grants, Map<UUID, Set<String>> teamPermissions) {
        StudioPrincipal principal = StudioPrincipal.live(userId, "u", false);
        when(access.of(userId)).thenReturn(new AccessSnapshot(grants, teamPermissions));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static Grant global(String... permissions) {
        return new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(permissions));
    }

    private void owns(UUID team, UUID cluster, String kind, String pattern) {
        ownedRows.add(new TeamPatternEntity(team, cluster, kind, pattern));
    }

    private void shares(UUID owner, UUID target, UUID cluster, String kind, String pattern, Set<String> role) {
        UUID roleId = UUID.randomUUID();
        rolePermissionRows.put(roleId, role);
        shareRows.add(new TeamShareEntity(owner, target, cluster, kind, pattern, roleId));
    }

    // ---- role grants --------------------------------------------------------------------------------

    @Test
    void noAuthenticationDeniesEverything() {
        SecurityContextHolder.clearContext();

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("a"), QUEUE_READ)).isFalse();
        assertThat(resolver.canSeeCluster(clusterId)).isFalse();
    }

    @Test
    void aGlobalGrantCoversAnyCluster() {
        access(Set.of(global(Permissions.CLUSTER_READ)), Map.of());

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(clusterId, ClusterPermissions.CLUSTER_WRITE)).isFalse();
    }

    @Test
    void anEnvironmentGrantCoversOnlyItsMembers() {
        when(environments.environmentOf(clusterId)).thenReturn(environmentId);
        when(environments.environmentOf(otherClusterId)).thenReturn(null);
        access(
                Set.of(new Grant(Grant.ScopeType.ENVIRONMENT, environmentId, Set.of(Permissions.CLUSTER_READ))),
                Map.of());

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isFalse();
    }

    @Test
    void aClusterGrantDoesNotLeakToASiblingClusterInTheSameEnvironment() {
        when(environments.environmentOf(clusterId)).thenReturn(environmentId);
        when(environments.environmentOf(otherClusterId)).thenReturn(environmentId);
        access(Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.CLUSTER_READ))), Map.of());

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isFalse();
    }

    @Test
    void wildcardsGrantWhatTheyCover() {
        access(Set.of(global(Permissions.WILDCARD)), Map.of());
        assertThat(resolver.can(clusterId, QUEUE_PURGE)).isTrue();
        assertThat(resolver.can(Permissions.USER_ADMIN)).isTrue();

        access(Set.of(global("message:*")), Map.of());
        assertThat(resolver.can(clusterId, MESSAGE_SEND)).isTrue();
        assertThat(resolver.can(clusterId, MessagePermissions.MESSAGE_DELETE)).isTrue();
        assertThat(resolver.can(clusterId, QUEUE_PURGE)).isFalse();
    }

    @Test
    void rolesAddUpAcrossGrants() {
        access(
                Set.of(
                        new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.CLUSTER_READ)),
                        new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(MESSAGE_SEND))),
                Map.of());

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(clusterId, MESSAGE_SEND)).isTrue();
        assertThat(resolver.can(clusterId, QUEUE_PURGE)).isFalse();
    }

    // ---- scope ---------------------------------------------------------------------------------------

    @Test
    void aGlobalPermissionTakesEffectOnlyThroughAGlobalGrant() {
        when(environments.environmentOf(clusterId)).thenReturn(environmentId);
        access(
                Set.of(
                        new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.USER_ADMIN)),
                        new Grant(Grant.ScopeType.ENVIRONMENT, environmentId, Set.of(Permissions.USER_ADMIN))),
                Map.of());

        assertThat(resolver.can(clusterId, Permissions.USER_ADMIN)).isFalse();
        assertThat(resolver.can(Permissions.USER_ADMIN)).isFalse();

        access(Set.of(global(Permissions.USER_ADMIN)), Map.of());
        assertThat(resolver.can(clusterId, Permissions.USER_ADMIN)).isTrue();
        assertThat(resolver.can(Permissions.USER_ADMIN)).isTrue();
    }

    @Test
    void aPermissionOutsideTheCatalogueGrantsNothingNotEvenThroughAWildcard() {
        catalogue.remove(AlertPermissions.ALERT_READ);
        access(Set.of(global(Permissions.WILDCARD, AlertPermissions.ALERT_READ, "alert:*")), Map.of());

        assertThat(resolver.can(clusterId, AlertPermissions.ALERT_READ)).isFalse();
        assertThat(resolver.can(clusterId, "nothing:declared")).isFalse();
        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
    }

    @Test
    void aResourcePermissionAskedAboutAClusterAsAWholeIsAnsweredByGrantsOnly() {
        UUID team = orders;
        owns(team, clusterId, "BOTH", "orders.#");
        access(Set.of(), Map.of(team, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, QUEUE_PURGE)).isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isTrue();
    }

    // ---- owning team ---------------------------------------------------------------------------------

    @Test
    void aTeamMemberActsOnWhatTheTeamOwnsAndNothingElse() {
        owns(orders, clusterId, "BOTH", "orders.#");
        owns(billing, clusterId, "BOTH", "billing.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders"), QUEUE_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.queue("billing.in"), QUEUE_PURGE))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("legacy.inbox"), QUEUE_READ))
                .isFalse();
    }

    @Test
    void theTeamRoleLimitsWhatTheMemberMayDo() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER));

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isFalse();
    }

    @Test
    void aTeamsNamesOnOneClusterSayNothingOnAnother() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.can(otherClusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isFalse();
        assertThat(resolver.canSeeCluster(otherClusterId)).isFalse();
    }

    @Test
    void aPatternOfOneKindDoesNotReachTheOther() {
        owns(orders, clusterId, "QUEUE", "orders.#");
        owns(billing, clusterId, "ADDRESS", "billing.#");
        owns(billing, clusterId, "BOTH", "both.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR, billing, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.address("orders.in"), MESSAGE_SEND))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.address("billing.in"), MESSAGE_SEND))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.queue("billing.in"), QUEUE_PURGE))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("both.x"), QUEUE_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.address("both.x"), MESSAGE_SEND))
                .isTrue();
    }

    @Test
    void aPermissionIsOnlyCheckedOnTheKindsItActsOn() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        // message:send acts on an address, so a team right on a queue named the same does not carry it
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), MESSAGE_SEND))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.address("orders.in"), MESSAGE_SEND))
                .isTrue();
    }

    @Test
    void teamsRightsAddUpAcrossTeams() {
        owns(orders, clusterId, "BOTH", "orders.#");
        owns(billing, clusterId, "BOTH", "billing.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER, billing, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.queue("billing.x"), QUEUE_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.x"), QUEUE_PURGE))
                .isFalse();
    }

    // ---- shares --------------------------------------------------------------------------------------

    @Test
    void aShareGivesTheReceivingTeamItsRoleOnWhatItCovers() {
        owns(orders, clusterId, "BOTH", "orders.#");
        shares(orders, billing, clusterId, "BOTH", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.events.paid"), QUEUE_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.events.paid"), QUEUE_PURGE))
                .as("the share's role, not the member's own team role")
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_READ))
                .isFalse();
    }

    @Test
    void aShareDoesNotReachTheTeamsThatDidNotReceiveIt() {
        UUID audit = UUID.randomUUID();
        owns(orders, clusterId, "BOTH", "orders.#");
        shares(orders, billing, clusterId, "BOTH", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(audit, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.events.paid"), QUEUE_READ))
                .isFalse();
    }

    @Test
    void aShareOutsideWhatTheOwnerOwnsGrantsNothing() {
        owns(orders, clusterId, "BOTH", "orders.#");
        shares(orders, billing, clusterId, "BOTH", "billing.#", TEAM_VIEWER);
        shares(orders, billing, clusterId, "BOTH", "orders.#.x", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.queue("billing.in"), QUEUE_READ))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.a.x"), QUEUE_READ))
                .isTrue();
    }

    @Test
    void aShareOfAKindTheOwnerOnlyOwnsInPartGrantsOnlyThatPart() {
        owns(orders, clusterId, "QUEUE", "orders.#");
        shares(orders, billing, clusterId, "BOTH", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_VIEWER));

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.events.a"), QUEUE_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.address("orders.events.a"), Permissions.ADDRESS_READ))
                .isFalse();
    }

    // ---- platform and team ---------------------------------------------------------------------------

    @Test
    void aPlatformGrantCoversEveryResourceOnTheClusterOwnedOrNot() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(QUEUE_PURGE))), Map.of());

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.queue("legacy.inbox"), QUEUE_PURGE))
                .isTrue();
        assertThat(resolver.can(otherClusterId, ResourceRef.queue("legacy.inbox"), QUEUE_PURGE))
                .isFalse();
    }

    @Test
    void platformGrantsAndTeamRolesAdd() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(
                Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(QUEUE_READ))),
                Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.queue("legacy.inbox"), QUEUE_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.queue("legacy.inbox"), QUEUE_PURGE))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isTrue();
    }

    @Test
    void aGlobalPermissionIsNotReachedThroughATeamRole() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(Set.of(), Map.of(orders, Set.of(Permissions.USER_ADMIN, Permissions.CLUSTER_READ)));

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), Permissions.USER_ADMIN))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), Permissions.CLUSTER_READ))
                .isFalse();
    }

    @Test
    void aTeamRolePermissionOutsideTheCatalogueGrantsNothing() {
        owns(orders, clusterId, "BOTH", "orders.#");
        catalogue.remove(QUEUE_PURGE);
        access(Set.of(), Map.of(orders, Set.of(Permissions.WILDCARD)));

        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_READ))
                .isTrue();
    }

    @Test
    void aPrincipalThatCarriesItsOwnGrantsGetsNoTeamAccess() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));
        StudioPrincipal key = new StudioPrincipal(
                userId, "key", Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(QUEUE_READ))), false, "ci");

        assertThat(resolver.can(key, clusterId, ResourceRef.queue("legacy.q"), QUEUE_READ))
                .isTrue();
        assertThat(resolver.can(key, clusterId, ResourceRef.queue("orders.in"), QUEUE_PURGE))
                .isFalse();
    }

    // ---- gating --------------------------------------------------------------------------------------

    @Test
    void canAnywhereOffersWhatSomeTeamOrShareOnTheClusterAllows() {
        owns(orders, clusterId, "QUEUE", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.canAnywhere(clusterId, QUEUE_PURGE)).isTrue();
        assertThat(resolver.canAnywhere(clusterId, MESSAGE_SEND))
                .as("the team owns no address here")
                .isFalse();
        assertThat(resolver.canAnywhere(clusterId, MessagePermissions.MESSAGE_MOVE))
                .as("the team role lacks it")
                .isFalse();
        assertThat(resolver.canAnywhere(otherClusterId, QUEUE_PURGE)).isFalse();
    }

    @Test
    void canAnywhereCountsASharedPattern() {
        owns(orders, clusterId, "BOTH", "orders.#");
        shares(orders, billing, clusterId, "BOTH", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_OPERATOR));

        assertThat(resolver.canAnywhere(clusterId, QUEUE_READ)).isTrue();
        assertThat(resolver.canAnywhere(clusterId, QUEUE_PURGE)).isFalse();
    }

    @Test
    void aClusterIsSeenThroughAnyReadOfItsResourcesButGrantsNothingClusterWide() {
        owns(orders, clusterId, "ADDRESS", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER));

        assertThat(resolver.canSeeCluster(clusterId)).isTrue();
        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isFalse();
        assertThat(resolver.canSeeCluster(otherClusterId)).isFalse();

        access(Set.of(new Grant(Grant.ScopeType.CLUSTER, otherClusterId, Set.of(Permissions.CLUSTER_READ))), Map.of());
        assertThat(resolver.canSeeCluster(otherClusterId)).isTrue();
    }

    @Test
    void aUserWithNoAccessSeesNothing() {
        owns(orders, clusterId, "BOTH", "orders.#");
        when(access.of(userId)).thenReturn(AccessSnapshot.NONE);

        assertThat(resolver.canSeeCluster(clusterId)).isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.queue("orders.in"), QUEUE_READ))
                .isFalse();
    }

    // ---- filtering a list ---------------------------------------------------------------------------

    @Test
    void aFilterLetsThroughOnlyTheNamesTheCallersTeamsCover() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER));

        ResourceFilter filter = resolver.filter(clusterId, ResourceKind.QUEUE);

        assertThat(filter.everything()).isFalse();
        assertThat(filter.readable("orders.in")).isTrue();
        assertThat(filter.readable("billing.in")).isFalse();
        assertThat(filter.readable(null)).isFalse();
    }

    @Test
    void aFilterOfACallerWhoReadsTheWholeClusterNeedsNoPerNameCheck() {
        access(Set.of(global(QUEUE_READ, QUEUE_PURGE)), Map.of());

        ResourceFilter filter = resolver.filter(clusterId, ResourceKind.QUEUE);

        assertThat(filter.everything()).isTrue();
        assertThat(filter.readable("anything")).isTrue();
        assertThat(filter.allowedActions("anything")).containsExactly(QUEUE_PURGE, QUEUE_READ);
    }

    @Test
    void aRowCarriesTheActionsOfItsKindThatTheCallerHoldsOnIt() {
        owns(orders, clusterId, "BOTH", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        ResourceFilter queues = resolver.filter(clusterId, ResourceKind.QUEUE);
        ResourceFilter addresses = resolver.filter(clusterId, ResourceKind.ADDRESS);

        assertThat(queues.allowedActions("orders.in"))
                .contains(QUEUE_READ, QUEUE_PURGE, MessagePermissions.MESSAGE_DELETE)
                .doesNotContain(MESSAGE_SEND, Permissions.ADDRESS_READ);
        assertThat(addresses.allowedActions("orders.in"))
                .contains(Permissions.ADDRESS_READ, MESSAGE_SEND)
                .doesNotContain(QUEUE_PURGE);
        assertThat(queues.allowedActions("billing.in")).isEmpty();
    }

    @Test
    void aShareAddsItsActionsToTheRowsItCovers() {
        owns(billing, clusterId, "BOTH", "billing.#");
        shares(billing, orders, clusterId, "BOTH", "billing.in", TEAM_VIEWER);
        access(Set.of(), Map.of(orders, TEAM_VIEWER));

        ResourceFilter queues = resolver.filter(clusterId, ResourceKind.QUEUE);

        assertThat(queues.readable("billing.in")).isTrue();
        assertThat(queues.readable("billing.payments")).isFalse();
        assertThat(queues.allowedActions("billing.in")).containsExactly(MessagePermissions.MESSAGE_READ, QUEUE_READ);
    }

    // ---- where a caller may create ------------------------------------------------------------------

    @Test
    void thePatternsACallerMayCreateUnderAreThoseOfTheirTeamsThatTheirRoleAllowsIt() {
        owns(orders, clusterId, "BOTH", "orders.#");
        owns(billing, clusterId, "BOTH", "billing.#");
        shares(billing, orders, clusterId, "QUEUE", "billing.shared.#", Set.of(QUEUE_READ, QUEUE_CREATE));
        access(Set.of(), Map.of(orders, Set.of(QUEUE_READ, QUEUE_CREATE)));

        assertThat(resolver.patternsHolding(clusterId, ResourceKind.QUEUE, QUEUE_CREATE))
                .containsExactly("billing.shared.#", "orders.#");
        assertThat(resolver.patternsHolding(clusterId, ResourceKind.ADDRESS, QUEUE_CREATE))
                .isEmpty();
        assertThat(resolver.patternsHolding(otherClusterId, ResourceKind.QUEUE, QUEUE_CREATE))
                .isEmpty();
    }
}

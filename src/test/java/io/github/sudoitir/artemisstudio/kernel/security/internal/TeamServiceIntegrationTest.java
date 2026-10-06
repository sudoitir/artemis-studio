package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.PatternKind;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceNames;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceRef;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.ShareRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.TeamView;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The team service's rules (team-access spec): what a pattern may be, who may change what, which roles a team
 * may hold, and that every change is audited and applies to the next request.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class TeamServiceIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    ResourceNames names;

    @Autowired
    TeamService teams;

    @Autowired
    RoleService roleService;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    PermissionResolver perm;

    @Autowired
    JdbcTemplate jdbc;

    UUID prod;
    UUID staging;

    @BeforeEach
    void setUp() {
        prod = clusters.save(new ClusterEntity("prod-" + UUID.randomUUID(), null, null))
                .getId();
        staging = clusters.save(new ClusterEntity("staging-" + UUID.randomUUID(), null, null))
                .getId();
    }

    private UUID team(String name) {
        return teams.create(name + "-" + UUID.randomUUID()).id();
    }

    private UUID role(String name) {
        return roles.findByName(name).orElseThrow().getId();
    }

    private UUID user() {
        String name = "member-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(name, null, "{noop}x");
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    private static PatternRequest pattern(UUID cluster, PatternKind kind, String pattern) {
        return new PatternRequest(cluster, kind, pattern);
    }

    private void asUser(UUID userId) {
        StudioPrincipal principal = StudioPrincipal.live(userId, "member", false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private int audited(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ?", Integer.class, action);
    }

    // ---- teams --------------------------------------------------------------------------------------

    @Test
    void aTeamIsCreatedRenamedAndDeletedAndEachIsAudited() {
        int created = audited("TEAM_CREATE");
        UUID id = team("orders");

        teams.rename(id, "Renamed-" + id);
        assertThat(teams.get(id).name()).isEqualTo("Renamed-" + id);
        teams.delete(id);

        assertThat(audited("TEAM_CREATE")).isEqualTo(created + 1);
        assertThat(audited("TEAM_RENAME")).isPositive();
        assertThat(audited("TEAM_DELETE")).isPositive();
        assertThatThrownBy(() -> teams.get(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void teamNamesAreUniqueIgnoringCase() {
        UUID id = team("Orders");
        String name = teams.get(id).name();

        assertThatThrownBy(() -> teams.create(name.toUpperCase()))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).slug())
                .isEqualTo("duplicate-team-name");
    }

    @Test
    void onlyAUserAdministratorCreatesATeam() {
        asUser(user());

        assertThatThrownBy(() -> teams.create("sneaky-" + UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
    }

    // ---- patterns -----------------------------------------------------------------------------------

    @Test
    void aMalformedPatternIsRefusedNamingTheFault() {
        UUID id = team("t");

        assertThatThrownBy(() -> teams.addPattern(id, pattern(prod, PatternKind.BOTH, "a..b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty word");
        assertThatThrownBy(() -> teams.addPattern(id, pattern(prod, PatternKind.BOTH, "a*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mixes a wildcard");
    }

    @Test
    void aPatternThatOverlapsAnotherTeamIsRefusedNamingThatTeamAndPattern() {
        UUID orders = team("orders");
        UUID audit = team("audit");
        teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "orders.#"));

        assertThatThrownBy(() -> teams.addPattern(audit, pattern(prod, PatternKind.QUEUE, "orders.audit.*")))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.slug()).isEqualTo("team-pattern-overlap");
                    assertThat(e.getMessage()).contains(teams.get(orders).name(), "orders.#");
                });
    }

    @Test
    void theSamePatternOnAnotherClusterOrAnotherKindOrDisjointNamesIsAllowed() {
        UUID orders = team("orders");
        UUID other = team("other");
        teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "orders.#"));

        teams.addPattern(other, pattern(staging, PatternKind.QUEUE, "orders.#"));
        teams.addPattern(other, pattern(prod, PatternKind.ADDRESS, "orders.#"));
        teams.addPattern(other, pattern(prod, PatternKind.QUEUE, "billing.*"));

        assertThat(teams.get(other).patterns()).hasSize(3);
    }

    @Test
    void bothOverlapsEitherKind() {
        UUID orders = team("orders");
        UUID other = team("other");
        teams.addPattern(orders, pattern(prod, PatternKind.ADDRESS, "orders.#"));

        assertThatThrownBy(() -> teams.addPattern(other, pattern(prod, PatternKind.BOTH, "orders.in")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aTeamMayOverlapItself() {
        UUID orders = team("orders");
        teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "orders.#"));

        teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "orders.in"));

        assertThat(teams.get(orders).patterns()).hasSize(2);
        assertThatThrownBy(() -> teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "orders.in")))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("duplicate-team-pattern"));
    }

    @Test
    void aPatternNeedsAnExistingCluster() {
        UUID id = team("t");

        assertThatThrownBy(() -> teams.addPattern(id, pattern(UUID.randomUUID(), PatternKind.BOTH, "a.#")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void removingAPatternEndsTheAccessItGave() {
        UUID orders = team("orders");
        UUID alice = user();
        var added = teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "orders.#"));
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, alice, null, null, role("TEAM_OPERATOR")));

        asUser(alice);
        assertThat(perm.can(prod, ResourceRef.queue("orders.in"), "queue:purge"))
                .isTrue();

        new AdminAuthenticationExtension().beforeEach(null);
        teams.removePattern(orders, added.id());

        asUser(alice);
        assertThat(perm.can(prod, ResourceRef.queue("orders.in"), "queue:purge"))
                .isFalse();
    }

    @Test
    void deletingATeamEndsItsMembersAccessAtOnce() {
        UUID orders = team("orders");
        UUID alice = user();
        teams.addPattern(orders, pattern(prod, PatternKind.BOTH, "orders.#"));
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, alice, null, null, role("TEAM_VIEWER")));
        asUser(alice);
        assertThat(perm.canSeeCluster(prod)).isTrue();

        new AdminAuthenticationExtension().beforeEach(null);
        teams.delete(orders);

        asUser(alice);
        assertThat(perm.canSeeCluster(prod)).isFalse();
    }

    // ---- preview and unowned -------------------------------------------------------------------------

    @Test
    void thePreviewCountsWhatThePatternCoversAndShowsAtMostTwentyExamples() {
        UUID id = team("t");
        when(names.queues(prod))
                .thenReturn(IntStream.range(0, 30).mapToObj(i -> "orders.q" + i).toList());
        when(names.addresses(prod)).thenReturn(List.of("orders", "billing.in"));

        var preview = teams.preview(id, prod, PatternKind.BOTH, "orders.#");

        assertThat(preview.queueMatches()).isEqualTo(30);
        assertThat(preview.queueExamples()).hasSize(20);
        assertThat(preview.addressMatches()).isEqualTo(1);
        assertThat(preview.addressExamples()).containsExactly("orders");
        assertThat(preview.conflicts()).isEmpty();
    }

    @Test
    void thePreviewNamesTheClashesAndOnlyCountsTheKindAsked() {
        UUID orders = team("orders");
        UUID other = team("other");
        teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "orders.#"));
        when(names.queues(prod)).thenReturn(List.of("orders.in"));
        when(names.addresses(prod)).thenReturn(List.of("orders.in"));

        var preview = teams.preview(other, prod, PatternKind.QUEUE, "orders.*");

        assertThat(preview.addressMatches()).isZero();
        assertThat(preview.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.teamId()).isEqualTo(orders);
            assertThat(c.pattern()).isEqualTo("orders.#");
        });
    }

    @Test
    void unownedListsTheNamesNoTeamOwnsOfThatKind() {
        UUID orders = team("orders");
        teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "orders.#"));
        when(names.queues(prod)).thenReturn(List.of("orders.in", "legacy.inbox"));
        when(names.addresses(prod)).thenReturn(List.of("orders.in", "legacy.inbox"));

        assertThat(teams.unowned(prod, ResourceKind.QUEUE)).containsExactly("legacy.inbox");
        assertThat(teams.unowned(prod, ResourceKind.ADDRESS)).containsExactly("orders.in", "legacy.inbox");
    }

    // ---- members ------------------------------------------------------------------------------------

    @Test
    void aMemberMustHoldATeamRole() {
        UUID id = team("t");

        assertThatThrownBy(() -> teams.addMember(
                        id, new MemberRequest(PrincipalType.USER, user(), null, null, role("OPERATOR"))))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("not-a-team-role"));
    }

    @Test
    void aUserIsAMemberOnceAndTheRoleCanChange() {
        UUID id = team("t");
        UUID alice = user();
        var member = teams.addMember(id, new MemberRequest(PrincipalType.USER, alice, null, null, role("TEAM_VIEWER")));

        assertThatThrownBy(() -> teams.addMember(
                        id, new MemberRequest(PrincipalType.USER, alice, null, null, role("TEAM_VIEWER"))))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("team-member-exists"));
        var changed = teams.changeMemberRole(id, member.id(), role("TEAM_OPERATOR"));

        assertThat(changed.roleName()).isEqualTo("TEAM_OPERATOR");
        teams.removeMember(id, member.id());
        assertThat(teams.get(id).members()).isEmpty();
    }

    @Test
    void aGroupMemberNeedsAKnownExternalProviderAndAGroup() {
        UUID id = team("t");

        assertThatThrownBy(() -> teams.addMember(
                        id,
                        new MemberRequest(
                                PrincipalType.GROUP, null, "no-such-provider", "orders", role("TEAM_VIEWER"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> teams.addMember(
                        id, new MemberRequest(PrincipalType.GROUP, null, null, null, role("TEAM_VIEWER"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTeamAdminManagesTheirTeamsMembersWithRolesNoWiderThanTheirOwn() {
        UUID orders = team("orders");
        UUID boss = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        var wider = roleService.create(new RoleRequest(
                "wider-" + UUID.randomUUID(), List.of("queue:read", "queue:update", "capture:write"), false, true));
        UUID newcomer = user();

        asUser(boss);
        var added = teams.addMember(
                orders, new MemberRequest(PrincipalType.USER, newcomer, null, null, role("TEAM_VIEWER")));
        teams.changeMemberRole(orders, added.id(), role("TEAM_OPERATOR"));

        assertThatThrownBy(() -> teams.changeMemberRole(orders, added.id(), wider.id()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("capture:write");
        assertThat(teams.get(orders).members()).hasSize(2);
        teams.removeMember(orders, added.id());
    }

    @Test
    void aTeamAdminCannotChangePatternsOrSharesOrSeeOtherTeams() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        UUID boss = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));

        asUser(boss);

        assertThatThrownBy(() -> teams.addPattern(orders, pattern(prod, PatternKind.BOTH, "a.#")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> teams.addShare(
                        orders, new ShareRequest(billing, prod, PatternKind.BOTH, "a.#", role("TEAM_VIEWER"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> teams.get(billing)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> teams.addMember(
                        billing, new MemberRequest(PrincipalType.USER, user(), null, null, role("TEAM_VIEWER"))))
                .isInstanceOf(NotFoundException.class);
        assertThat(teams.list()).extracting(s -> s.id()).containsExactly(orders);
    }

    @Test
    void aMemberWhoIsNotATeamAdminCannotManageMembers() {
        UUID orders = team("orders");
        UUID viewer = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, viewer, null, null, role("TEAM_OPERATOR")));

        asUser(viewer);

        assertThatThrownBy(() -> teams.addMember(
                        orders, new MemberRequest(PrincipalType.USER, user(), null, null, role("TEAM_VIEWER"))))
                .isInstanceOf(NotFoundException.class);
        assertThat(teams.list()).isEmpty();
    }

    // ---- shares -------------------------------------------------------------------------------------

    @Test
    void aSharedPatternMustLieWithinOneOfTheOwnersPatterns() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        teams.addPattern(orders, pattern(prod, PatternKind.BOTH, "orders.#"));
        teams.addPattern(orders, pattern(prod, PatternKind.QUEUE, "audit.*"));
        UUID viewer = role("TEAM_VIEWER");

        var share =
                teams.addShare(orders, new ShareRequest(billing, prod, PatternKind.BOTH, "orders.events.#", viewer));

        assertThat(share.covered()).isTrue();
        assertThat(teams.get(billing).sharesIn()).hasSize(1);
        assertThatThrownBy(() ->
                        teams.addShare(orders, new ShareRequest(billing, prod, PatternKind.BOTH, "billing.#", viewer)))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("share-outside-owner"));
        assertThatThrownBy(() ->
                        teams.addShare(orders, new ShareRequest(billing, prod, PatternKind.BOTH, "audit.x", viewer)))
                .as("the owner owns audit.* for queues only")
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> teams.addShare(
                        orders, new ShareRequest(billing, staging, PatternKind.BOTH, "orders.events.#", viewer)))
                .as("owned on prod, not on staging")
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aShareNeedsAnotherTeamAndATeamRole() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        teams.addPattern(orders, pattern(prod, PatternKind.BOTH, "orders.#"));

        assertThatThrownBy(() -> teams.addShare(
                        orders, new ShareRequest(orders, prod, PatternKind.BOTH, "orders.#", role("TEAM_VIEWER"))))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("share-with-self"));
        assertThatThrownBy(() -> teams.addShare(
                        orders, new ShareRequest(billing, prod, PatternKind.BOTH, "orders.#", role("VIEWER"))))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("not-a-team-role"));
        assertThatThrownBy(() -> teams.addShare(
                        orders,
                        new ShareRequest(UUID.randomUUID(), prod, PatternKind.BOTH, "orders.#", role("TEAM_VIEWER"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aShareGivesTheReceivingMembersItsRoleAndEndsWhenRemoved() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        UUID carol = user();
        teams.addPattern(orders, pattern(prod, PatternKind.BOTH, "orders.#"));
        teams.addMember(billing, new MemberRequest(PrincipalType.USER, carol, null, null, role("TEAM_OPERATOR")));
        var share = teams.addShare(
                orders, new ShareRequest(billing, prod, PatternKind.BOTH, "orders.events.#", role("TEAM_VIEWER")));

        asUser(carol);
        assertThat(perm.can(prod, ResourceRef.queue("orders.events.a"), "queue:read"))
                .isTrue();
        assertThat(perm.can(prod, ResourceRef.queue("orders.events.a"), "queue:purge"))
                .isFalse();

        new AdminAuthenticationExtension().beforeEach(null);
        teams.removeShare(orders, share.id());

        asUser(carol);
        assertThat(perm.can(prod, ResourceRef.queue("orders.events.a"), "queue:read"))
                .isFalse();
    }

    @Test
    void aShareThatTheOwnersPatternsNoLongerCoverIsShownAsNotCovered() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        var owned = teams.addPattern(orders, pattern(prod, PatternKind.BOTH, "orders.#"));
        teams.addShare(
                orders, new ShareRequest(billing, prod, PatternKind.BOTH, "orders.events.#", role("TEAM_VIEWER")));

        teams.removePattern(orders, owned.id());

        TeamView view = teams.get(orders);
        assertThat(view.sharesOut())
                .singleElement()
                .satisfies(s -> assertThat(s.covered()).isFalse());
    }

    @Test
    void everyChangeIsAudited() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        var owned = teams.addPattern(orders, pattern(prod, PatternKind.BOTH, "orders.#"));
        var member =
                teams.addMember(orders, new MemberRequest(PrincipalType.USER, user(), null, null, role("TEAM_VIEWER")));
        var share = teams.addShare(
                orders, new ShareRequest(billing, prod, PatternKind.BOTH, "orders.#", role("TEAM_VIEWER")));
        teams.changeMemberRole(orders, member.id(), role("TEAM_OPERATOR"));
        teams.removeShare(orders, share.id());
        teams.removeMember(orders, member.id());
        teams.removePattern(orders, owned.id());

        for (String action : List.of(
                "TEAM_PATTERN_ADD",
                "TEAM_MEMBER_ADD",
                "TEAM_SHARE_ADD",
                "TEAM_MEMBER_ROLE",
                "TEAM_SHARE_REMOVE",
                "TEAM_MEMBER_REMOVE",
                "TEAM_PATTERN_REMOVE")) {
            assertThat(audited(action)).as(action).isPositive();
        }
    }

    @Autowired
    TeamMemberRepository teamMembers;

    // ---- the limits of a team admin ------------------------------------------------------------------

    private UUID widerRole() {
        return roleService
                .create(new RoleRequest(
                        "wider-" + UUID.randomUUID(),
                        List.of("queue:read", "queue:update", "capture:write"),
                        false,
                        true))
                .id();
    }

    @Test
    void aTeamAdminCannotAddAChangeOrRemoveADirectoryGroupButAUserAdministratorCan() {
        UUID orders = team("orders");
        UUID boss = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        UUID group = teamMembers
                .save(io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberEntity.group(
                        orders, "dir", "eng", role("TEAM_VIEWER")))
                .getId();

        asUser(boss);

        assertThatThrownBy(() -> teams.addMember(
                        orders, new MemberRequest(PrincipalType.GROUP, null, "dir", "ops", role("TEAM_VIEWER"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("user administrator");
        assertThatThrownBy(() -> teams.changeMemberRole(orders, group, role("TEAM_OPERATOR")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> teams.removeMember(orders, group)).isInstanceOf(AccessDeniedException.class);

        new AdminAuthenticationExtension().beforeEach(null);
        teams.changeMemberRole(orders, group, role("TEAM_OPERATOR"));
        teams.removeMember(orders, group);
    }

    @Test
    void aTeamAdminCannotDemoteOrRemoveAMemberWhoseRoleHoldsMoreThanTheirs() {
        UUID orders = team("orders");
        UUID boss = user();
        UUID senior = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        var member = teams.addMember(orders, new MemberRequest(PrincipalType.USER, senior, null, null, widerRole()));

        asUser(boss);

        assertThatThrownBy(() -> teams.changeMemberRole(orders, member.id(), role("TEAM_VIEWER")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("capture:write");
        assertThatThrownBy(() -> teams.removeMember(orders, member.id())).isInstanceOf(AccessDeniedException.class);
        assertThat(teams.get(orders).members()).hasSize(2);

        new AdminAuthenticationExtension().beforeEach(null);
        teams.changeMemberRole(orders, member.id(), role("TEAM_VIEWER"));
        teams.removeMember(orders, member.id());
    }

    @Test
    void aTeamAdminMayStillChangeAndRemoveMembersWithinTheirOwnRights() {
        UUID orders = team("orders");
        UUID boss = user();
        UUID peer = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        var member =
                teams.addMember(orders, new MemberRequest(PrincipalType.USER, peer, null, null, role("TEAM_ADMIN")));

        asUser(boss);
        teams.changeMemberRole(orders, member.id(), role("TEAM_VIEWER"));
        teams.removeMember(orders, member.id());

        assertThat(teams.get(orders).members()).hasSize(1);
    }

    // ---- concurrency ---------------------------------------------------------------------------------

    @Test
    void twoTeamsAddingOverlappingPatternsAtOnceCannotBothSucceed() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID a = team("race-a");
            UUID b = team("race-b");
            UUID cluster = clusters.save(new ClusterEntity("race-" + UUID.randomUUID(), null, null))
                    .getId();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> addWhenReleased(start, a, cluster, "race.#"));
                var second = pool.submit(() -> addWhenReleased(start, b, cluster, "race.in.*"));
                start.countDown();
                int succeeded = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
                assertThat(succeeded).as("round " + round).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private boolean addWhenReleased(java.util.concurrent.CountDownLatch start, UUID team, UUID cluster, String pattern)
            throws InterruptedException {
        start.await();
        new AdminAuthenticationExtension().beforeEach(null);
        try {
            teams.addPattern(team, pattern(cluster, PatternKind.QUEUE, pattern));
            return true;
        } catch (ConflictException refused) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.AccessChanges;
import io.github.sudoitir.artemisstudio.kernel.security.AccessLoader;
import io.github.sudoitir.artemisstudio.kernel.security.AccessSnapshot;
import io.github.sudoitir.artemisstudio.kernel.security.AdministrationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.PatternKind;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceNames;
import io.github.sudoitir.artemisstudio.kernel.security.ResourcePattern;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.TeamIndex;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamShareEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamShareRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.MemberView;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternConflict;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternPreview;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PatternView;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.ShareRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.ShareView;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.TeamSummary;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.TeamView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teams: who owns which queue and address names on a cluster, who is a member, and what is shared
 * onward (team-access spec). A holder of {@code user:admin} does everything. A holder of
 * {@code team:admin} in a team (through the member's team role) may add, change and remove that team's
 * members, and only to roles whose permissions they hold in that team; they may not change its patterns or
 * shares. {@code team:admin} held through a global grant administers the members of every team. Every change
 * is audited and announced, so it applies on the next request.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final int EXAMPLES = 20;

    private static final String USER_ADMIN =
            "@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)";

    private final TeamRepository teams;
    private final TeamPatternRepository patterns;
    private final TeamMemberRepository members;
    private final TeamShareRepository shares;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final AppUserRepository users;
    private final PermissionResolver perm;
    private final AccessLoader access;
    private final TeamIndex index;
    private final AccessChanges accessChanges;
    private final AdministrationAudit audit;
    private final ScopeHierarchy clusters;
    private final IdentityProviderCatalog providers;
    private final ResourceNames names;

    // ---- teams --------------------------------------------------------------------------------------

    /** Every team to a user administrator; otherwise the teams the caller administers. */
    @Transactional(readOnly = true)
    public List<TeamSummary> list() {
        Authority authority = authority();
        return teams.findAllByOrderByName().stream()
                .filter(team -> authority.sees(team.getId()))
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamView get(UUID teamId) {
        TeamEntity team = requireTeam(teamId);
        if (!authority().sees(teamId)) {
            throw new NotFoundException("team", teamId);
        }
        return view(team);
    }

    @PreAuthorize(USER_ADMIN)
    @Transactional
    public TeamView create(String name) {
        String trimmed = name.strip();
        requireFreeName(trimmed, null);
        TeamEntity team = teams.save(new TeamEntity(trimmed));
        audit.changed("TEAM_CREATE", "team", trimmed, null);
        accessChanges.changed();
        return view(team);
    }

    @PreAuthorize(USER_ADMIN)
    @Transactional
    public TeamView rename(UUID teamId, String name) {
        TeamEntity team = requireTeam(teamId);
        String trimmed = name.strip();
        requireFreeName(trimmed, team);
        String before = team.getName();
        team.setName(trimmed);
        teams.save(team);
        audit.changed("TEAM_RENAME", "team", trimmed, Map.of("previousName", before));
        return view(team);
    }

    /** Removes the team with its patterns, memberships and shares, in both directions. */
    @PreAuthorize(USER_ADMIN)
    @Transactional
    public void delete(UUID teamId) {
        TeamEntity team = requireTeam(teamId);
        audit.changed(
                "TEAM_DELETE",
                "team",
                team.getName(),
                Map.of(
                        "patterns", patterns.findByTeamId(teamId).size(),
                        "members", members.countByTeamId(teamId)));
        teams.delete(team);
        accessChanges.changed();
    }

    // ---- patterns -----------------------------------------------------------------------------------

    /**
     * Adds a name pattern the team owns on a cluster, refused when some name on that cluster and kind could
     * match both it and a pattern of another team.
     */
    @PreAuthorize(USER_ADMIN)
    @Transactional
    public PatternView addPattern(UUID teamId, PatternRequest request) {
        TeamEntity team = requireTeam(teamId);
        ResourcePattern pattern = ResourcePattern.parse(request.pattern().strip());
        requireCluster(request.clusterId());
        boolean duplicate = patterns.findByTeamId(teamId).stream()
                .anyMatch(p -> p.getClusterId().equals(request.clusterId())
                        && p.getKind().equals(request.kind().name())
                        && p.getPattern().equals(pattern.text()));
        if (duplicate) {
            throw new ConflictException(
                    "duplicate-team-pattern", "Team '" + team.getName() + "' already owns '" + pattern + "' there.");
        }
        List<PatternConflict> conflicts = conflicts(request.clusterId(), request.kind(), pattern, teamId);
        if (!conflicts.isEmpty()) {
            PatternConflict first = conflicts.getFirst();
            throw new ConflictException(
                    "team-pattern-overlap",
                    "Some names could match both '" + pattern + "' and pattern '" + first.pattern() + "' of team '"
                            + first.teamName() + "' on this cluster. Teams may not own overlapping names.");
        }
        TeamPatternEntity saved = patterns.save(new TeamPatternEntity(
                teamId, request.clusterId(), request.kind().name(), pattern.text()));
        audit.changed(
                "TEAM_PATTERN_ADD",
                "team",
                team.getName(),
                Map.of(
                        "pattern", pattern.text(),
                        "kind", request.kind().name(),
                        "clusterId", request.clusterId().toString()));
        accessChanges.changed();
        return patternView(saved);
    }

    @PreAuthorize(USER_ADMIN)
    @Transactional
    public void removePattern(UUID teamId, UUID patternId) {
        TeamEntity team = requireTeam(teamId);
        TeamPatternEntity pattern = patterns.findByIdAndTeamId(patternId, teamId)
                .orElseThrow(() -> new NotFoundException("team pattern", patternId));
        patterns.delete(pattern);
        audit.changed(
                "TEAM_PATTERN_REMOVE",
                "team",
                team.getName(),
                Map.of(
                        "pattern", pattern.getPattern(),
                        "kind", pattern.getKind(),
                        "clusterId", pattern.getClusterId().toString()));
        accessChanges.changed();
    }

    /**
     * What a pattern would cover on a cluster right now (counts and up to 20 names of each kind) and which
     * patterns of other teams it would clash with, so an administrator sees both before adding it.
     */
    @PreAuthorize(USER_ADMIN)
    @Transactional(readOnly = true)
    public PatternPreview preview(UUID teamId, UUID clusterId, PatternKind kind, String text) {
        requireTeam(teamId);
        ResourcePattern pattern = ResourcePattern.parse(text.strip());
        requireCluster(clusterId);
        List<String> queues =
                kind.kinds().contains(ResourceKind.QUEUE) ? matching(pattern, names.queues(clusterId)) : List.of();
        List<String> addresses =
                kind.kinds().contains(ResourceKind.ADDRESS) ? matching(pattern, names.addresses(clusterId)) : List.of();
        return new PatternPreview(
                clusterId,
                kind,
                pattern.text(),
                queues.size(),
                addresses.size(),
                queues.stream().limit(EXAMPLES).toList(),
                addresses.stream().limit(EXAMPLES).toList(),
                conflicts(clusterId, kind, pattern, teamId));
    }

    /** The queue or address names of the cluster that no team owns: reachable only through platform grants. */
    @PreAuthorize(USER_ADMIN)
    @Transactional(readOnly = true)
    public List<String> unowned(UUID clusterId, ResourceKind kind) {
        requireCluster(clusterId);
        List<String> known = kind == ResourceKind.QUEUE ? names.queues(clusterId) : names.addresses(clusterId);
        return index.unowned(clusterId, kind, known);
    }

    // ---- members ------------------------------------------------------------------------------------

    @Transactional
    public MemberView addMember(UUID teamId, MemberRequest request) {
        TeamEntity team = requireTeam(teamId);
        Authority authority = memberAuthority(teamId);
        RoleEntity role = requireTeamRole(request.roleId());
        authority.requireMayAssign(teamId, role, permissionsOf(role));
        TeamMemberEntity member;
        String description;
        if (request.principalType() == PrincipalType.USER) {
            AppUserEntity user = request.userId() == null
                    ? null
                    : users.findById(request.userId()).orElse(null);
            if (user == null) {
                throw new NotFoundException("user", request.userId());
            }
            member = TeamMemberEntity.user(teamId, user.getId(), role.getId());
            description = "user " + user.getUsername();
        } else {
            String provider =
                    request.providerId() == null ? "" : request.providerId().strip();
            String group =
                    request.groupName() == null ? "" : request.groupName().strip();
            if (provider.isEmpty() || group.isEmpty()) {
                throw new IllegalArgumentException("A group member needs the identity provider and the group name.");
            }
            requireExternalProvider(provider);
            member = TeamMemberEntity.group(teamId, provider, group, role.getId());
            description = "group " + group + " of " + provider;
        }
        if (existing(teamId, member).isPresent()) {
            throw new ConflictException("team-member-exists", "The " + description + " is already a member.");
        }
        TeamMemberEntity saved = members.save(member);
        audit.changed("TEAM_MEMBER_ADD", "team", team.getName(), Map.of("member", description, "role", role.getName()));
        accessChanges.changed();
        return memberView(saved, roleNames(), usernames(List.of(saved)));
    }

    @Transactional
    public MemberView changeMemberRole(UUID teamId, UUID memberId, UUID roleId) {
        TeamEntity team = requireTeam(teamId);
        Authority authority = memberAuthority(teamId);
        TeamMemberEntity member = members.findByIdAndTeamId(memberId, teamId)
                .orElseThrow(() -> new NotFoundException("team member", memberId));
        RoleEntity role = requireTeamRole(roleId);
        authority.requireMayAssign(teamId, role, permissionsOf(role));
        member.setRoleId(role.getId());
        members.save(member);
        audit.changed(
                "TEAM_MEMBER_ROLE",
                "team",
                team.getName(),
                Map.of("member", describe(member, usernames(List.of(member))), "role", role.getName()));
        accessChanges.changed();
        return memberView(member, roleNames(), usernames(List.of(member)));
    }

    @Transactional
    public void removeMember(UUID teamId, UUID memberId) {
        TeamEntity team = requireTeam(teamId);
        memberAuthority(teamId);
        TeamMemberEntity member = members.findByIdAndTeamId(memberId, teamId)
                .orElseThrow(() -> new NotFoundException("team member", memberId));
        members.delete(member);
        audit.changed(
                "TEAM_MEMBER_REMOVE",
                "team",
                team.getName(),
                Map.of("member", describe(member, usernames(List.of(member)))));
        accessChanges.changed();
    }

    // ---- shares -------------------------------------------------------------------------------------

    /**
     * Shares a pattern with another team at a team role. The pattern must lie within what the owner owns
     * on the cluster: one of the owner's patterns of each kind it covers must contain it as a whole (a share
     * that only a union of the owner's patterns would cover is refused).
     */
    @PreAuthorize(USER_ADMIN)
    @Transactional
    public ShareView addShare(UUID ownerTeamId, ShareRequest request) {
        TeamEntity owner = requireTeam(ownerTeamId);
        if (ownerTeamId.equals(request.targetTeamId())) {
            throw new ConflictException("share-with-self", "A team cannot share with itself.");
        }
        TeamEntity target = requireTeam(request.targetTeamId());
        ResourcePattern pattern = ResourcePattern.parse(request.pattern().strip());
        requireCluster(request.clusterId());
        RoleEntity role = requireTeamRole(request.roleId());
        if (!covered(ownerTeamId, request.clusterId(), request.kind(), pattern)) {
            throw new ConflictException(
                    "share-outside-owner",
                    "'" + pattern + "' does not lie within the "
                            + request.kind().name().toLowerCase()
                            + " patterns team '" + owner.getName() + "' owns on this cluster; "
                            + "it must be inside one of them.");
        }
        boolean duplicate = shares.findByOwnerTeamId(ownerTeamId).stream()
                .anyMatch(s -> s.getTargetTeamId().equals(target.getId())
                        && s.getClusterId().equals(request.clusterId())
                        && s.getKind().equals(request.kind().name())
                        && s.getPattern().equals(pattern.text()));
        if (duplicate) {
            throw new ConflictException(
                    "duplicate-team-share",
                    "'" + pattern + "' is already shared with team '" + target.getName() + "'.");
        }
        TeamShareEntity saved = shares.save(new TeamShareEntity(
                ownerTeamId, target.getId(), request.clusterId(), request.kind().name(), pattern.text(), role.getId()));
        audit.changed(
                "TEAM_SHARE_ADD",
                "team",
                owner.getName(),
                Map.of(
                        "with", target.getName(),
                        "pattern", pattern.text(),
                        "kind", request.kind().name(),
                        "role", role.getName(),
                        "clusterId", request.clusterId().toString()));
        accessChanges.changed();
        return shareView(saved, teamNames(), roleNames());
    }

    @PreAuthorize(USER_ADMIN)
    @Transactional
    public void removeShare(UUID ownerTeamId, UUID shareId) {
        TeamEntity owner = requireTeam(ownerTeamId);
        TeamShareEntity share = shares.findByIdAndOwnerTeamId(shareId, ownerTeamId)
                .orElseThrow(() -> new NotFoundException("team share", shareId));
        shares.delete(share);
        audit.changed(
                "TEAM_SHARE_REMOVE",
                "team",
                owner.getName(),
                Map.of("pattern", share.getPattern(), "kind", share.getKind()));
        accessChanges.changed();
    }

    // ---- rules --------------------------------------------------------------------------------------

    /** The other teams' patterns on the cluster, of an intersecting kind, that some name could match with this one. */
    private List<PatternConflict> conflicts(UUID clusterId, PatternKind kind, ResourcePattern pattern, UUID team) {
        List<TeamEntity> all = teams.findAll();
        Map<UUID, String> teamNames = all.stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
        List<PatternConflict> found = new ArrayList<>();
        for (TeamPatternEntity other : patterns.findByClusterId(clusterId)) {
            PatternKind otherKind = PatternKind.valueOf(other.getKind());
            if (!other.getTeamId().equals(team)
                    && !Collections.disjoint(kind.kinds(), otherKind.kinds())
                    && ResourcePattern.overlaps(pattern, ResourcePattern.parse(other.getPattern()))) {
                found.add(new PatternConflict(
                        other.getTeamId(),
                        teamNames.getOrDefault(other.getTeamId(), "?"),
                        otherKind,
                        other.getPattern()));
            }
        }
        return found;
    }

    /** Whether, for every kind the share covers, one of the owner's patterns of that kind contains it. */
    private boolean covered(UUID ownerTeamId, UUID clusterId, PatternKind kind, ResourcePattern pattern) {
        List<TeamPatternEntity> owned = patterns.findByTeamId(ownerTeamId).stream()
                .filter(p -> p.getClusterId().equals(clusterId))
                .toList();
        return kind.kinds().stream()
                .allMatch(k -> owned.stream()
                        .filter(p -> PatternKind.valueOf(p.getKind()).kinds().contains(k))
                        .anyMatch(p -> ResourcePattern.covers(ResourcePattern.parse(p.getPattern()), pattern)));
    }

    private static List<String> matching(ResourcePattern pattern, List<String> candidates) {
        return candidates.stream().filter(pattern::matches).sorted().toList();
    }

    private void requireFreeName(String name, TeamEntity renaming) {
        boolean taken = teams.findAll().stream()
                .anyMatch(t -> t.getName().equalsIgnoreCase(name) && (renaming == null || !t.equals(renaming)));
        if (taken) {
            throw new ConflictException("duplicate-team-name", "A team named '" + name + "' already exists.");
        }
    }

    private TeamEntity requireTeam(UUID teamId) {
        return teams.findById(teamId).orElseThrow(() -> new NotFoundException("team", teamId));
    }

    private void requireCluster(UUID clusterId) {
        if (clusters.clusterName(clusterId) == null) {
            throw new NotFoundException("cluster", clusterId);
        }
    }

    private Set<String> permissionsOf(RoleEntity role) {
        return rolePermissions.findByIdRoleId(role.getId()).stream()
                .map(RolePermissionEntity::getAction)
                .collect(Collectors.toSet());
    }

    private RoleEntity requireTeamRole(UUID roleId) {
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        if (!role.isTeamAssignable()) {
            throw new ConflictException(
                    "not-a-team-role",
                    "Role '" + role.getName() + "' is not a team role: mark it team-assignable first.");
        }
        return role;
    }

    private void requireExternalProvider(String providerId) {
        boolean known = !LoginService.DEFAULT_PROVIDER.equals(providerId)
                && providers.providers().stream().anyMatch(p -> p.id().equals(providerId));
        if (!known) {
            throw new NotFoundException("identity provider", providerId);
        }
    }

    private java.util.Optional<TeamMemberEntity> existing(UUID teamId, TeamMemberEntity candidate) {
        return members.findByTeamId(teamId).stream()
                .filter(m -> m.getPrincipalType().equals(candidate.getPrincipalType())
                        && java.util.Objects.equals(m.getUserId(), candidate.getUserId())
                        && java.util.Objects.equals(m.getProviderId(), candidate.getProviderId())
                        && java.util.Objects.equals(m.getGroupName(), candidate.getGroupName()))
                .findFirst();
    }

    // ---- who may do what ----------------------------------------------------------------------------

    /**
     * What the caller may do with teams: everything when they administer users or hold {@code team:admin}
     * globally; otherwise only the members of the teams where their own team role holds {@code team:admin}.
     */
    private record Authority(boolean everything, Map<UUID, Set<String>> administered) {

        boolean sees(UUID teamId) {
            return everything || administered.containsKey(teamId);
        }

        /** A team admin may not give a role holding a permission they do not hold in the team. */
        void requireMayAssign(UUID teamId, RoleEntity role, Set<String> rolePermissions) {
            if (everything) {
                return;
            }
            Set<String> held = administered.getOrDefault(teamId, Set.of());
            List<String> beyond = rolePermissions.stream()
                    .filter(permission -> !Grant.covers(held, permission))
                    .sorted()
                    .toList();
            if (!beyond.isEmpty()) {
                throw new AccessDeniedException("Role '" + role.getName() + "' holds permissions you do not hold in"
                        + " this team: " + String.join(", ", beyond) + ".");
            }
        }
    }

    private Authority authority() {
        StudioPrincipal principal = currentPrincipal();
        if (principal == null) {
            throw new AccessDeniedException("Sign in to manage teams.");
        }
        if (perm.can(Permissions.USER_ADMIN) || perm.can(Permissions.TEAM_ADMIN)) {
            return new Authority(true, Map.of());
        }
        if (principal.pinned()) {
            return new Authority(false, Map.of());
        }
        AccessSnapshot snapshot = access.of(principal.userId());
        Map<UUID, Set<String>> administered = snapshot.teamPermissions().entrySet().stream()
                .filter(e -> Grant.covers(e.getValue(), Permissions.TEAM_ADMIN))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new Authority(false, administered);
    }

    /** The right to change {@code teamId}'s members; not found for a caller who may not even see the team. */
    private Authority memberAuthority(UUID teamId) {
        Authority authority = authority();
        if (!authority.sees(teamId)) {
            throw new NotFoundException("team", teamId);
        }
        return authority;
    }

    private static StudioPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof StudioPrincipal p ? p : null;
    }

    // ---- views --------------------------------------------------------------------------------------

    private TeamSummary summary(TeamEntity team) {
        return new TeamSummary(
                team.getId(),
                team.getName(),
                team.getCreatedAt(),
                (int) members.countByTeamId(team.getId()),
                patterns.findByTeamId(team.getId()).size(),
                shares.findByOwnerTeamId(team.getId()).size(),
                shares.findByTargetTeamId(team.getId()).size());
    }

    private TeamView view(TeamEntity team) {
        Map<UUID, String> roleNames = roleNames();
        Map<UUID, String> teamNames = teamNames();
        List<TeamMemberEntity> memberRows = members.findByTeamId(team.getId());
        Map<UUID, String> usernames = usernames(memberRows);
        return new TeamView(
                team.getId(),
                team.getName(),
                team.getCreatedAt(),
                patterns.findByTeamId(team.getId()).stream()
                        .map(TeamService::patternView)
                        .toList(),
                memberRows.stream()
                        .map(m -> memberView(m, roleNames, usernames))
                        .toList(),
                shares.findByOwnerTeamId(team.getId()).stream()
                        .map(s -> shareView(s, teamNames, roleNames))
                        .toList(),
                shares.findByTargetTeamId(team.getId()).stream()
                        .map(s -> shareView(s, teamNames, roleNames))
                        .toList());
    }

    private static PatternView patternView(TeamPatternEntity p) {
        return new PatternView(p.getId(), p.getClusterId(), PatternKind.valueOf(p.getKind()), p.getPattern());
    }

    private MemberView memberView(TeamMemberEntity m, Map<UUID, String> roleNames, Map<UUID, String> usernames) {
        return new MemberView(
                m.getId(),
                PrincipalType.valueOf(m.getPrincipalType()),
                m.getUserId(),
                m.getUserId() == null ? null : usernames.get(m.getUserId()),
                m.getProviderId(),
                m.getGroupName(),
                m.getRoleId(),
                roleNames.getOrDefault(m.getRoleId(), "?"));
    }

    private ShareView shareView(TeamShareEntity s, Map<UUID, String> teamNames, Map<UUID, String> roleNames) {
        PatternKind kind = PatternKind.valueOf(s.getKind());
        return new ShareView(
                s.getId(),
                s.getOwnerTeamId(),
                teamNames.getOrDefault(s.getOwnerTeamId(), "?"),
                s.getTargetTeamId(),
                teamNames.getOrDefault(s.getTargetTeamId(), "?"),
                s.getClusterId(),
                kind,
                s.getPattern(),
                s.getRoleId(),
                roleNames.getOrDefault(s.getRoleId(), "?"),
                covered(s.getOwnerTeamId(), s.getClusterId(), kind, ResourcePattern.parse(s.getPattern())));
    }

    private Map<UUID, String> roleNames() {
        return roles.findAll().stream().collect(Collectors.toMap(RoleEntity::getId, RoleEntity::getName));
    }

    private Map<UUID, String> teamNames() {
        return teams.findAll().stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
    }

    private Map<UUID, String> usernames(List<TeamMemberEntity> memberRows) {
        Set<UUID> ids = memberRows.stream()
                .map(TeamMemberEntity::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(AppUserEntity::getId, AppUserEntity::getUsername, (a, b) -> a));
    }

    private static String describe(TeamMemberEntity m, Map<UUID, String> usernames) {
        return m.getUserId() != null
                ? "user " + usernames.getOrDefault(m.getUserId(), m.getUserId().toString())
                : "group " + m.getGroupName() + " of " + m.getProviderId();
    }
}

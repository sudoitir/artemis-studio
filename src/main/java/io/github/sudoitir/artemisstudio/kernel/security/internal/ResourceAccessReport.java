package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceRef;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import io.github.sudoitir.artemisstudio.kernel.security.TeamIndex;
import io.github.sudoitir.artemisstudio.kernel.security.TeamRef;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.GrantSource;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.ResourceAccessView;
import io.github.sudoitir.artemisstudio.kernel.security.web.TeamViews.ResourceTeamGrant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who has access to one queue or address through teams: the owning team's roles with how many members hold
 * each, and every share that covers the name. For a user administrator or a team administrator, who are the
 * people that can act on a request for access.
 */
@Service
@RequiredArgsConstructor
public class ResourceAccessReport {

    private final TeamService teamService;
    private final TeamIndex index;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final RoleRepository roles;
    private final ScopeHierarchy clusters;

    @Transactional(readOnly = true)
    public ResourceAccessView of(UUID clusterId, ResourceRef ref) {
        teamService.requireTeamAdministrator();
        if (clusters.clusterName(clusterId) == null) {
            throw new NotFoundException("cluster", clusterId);
        }
        Map<UUID, String> teamNames =
                teams.findAll().stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
        Map<UUID, String> roleNames =
                roles.findAll().stream().collect(Collectors.toMap(RoleEntity::getId, RoleEntity::getName));
        TeamRef owner = index.ownerTeamOf(clusterId, ref).orElse(null);

        List<ResourceTeamGrant> grants = new ArrayList<>();
        if (owner != null) {
            members.findByTeamId(owner.id()).stream()
                    .collect(Collectors.groupingBy(TeamMemberEntity::getRoleId, Collectors.counting()))
                    .forEach((roleId, count) -> grants.add(new ResourceTeamGrant(
                            owner.id(),
                            owner.name(),
                            roleNames.getOrDefault(roleId, "?"),
                            GrantSource.OWNER,
                            null,
                            null,
                            count.intValue())));
        }
        for (TeamIndex.Shared share : index.sharesCovering(clusterId, ref)) {
            grants.add(new ResourceTeamGrant(
                    share.targetTeamId(),
                    teamNames.getOrDefault(share.targetTeamId(), "?"),
                    roleNames.getOrDefault(share.roleId(), "?"),
                    GrantSource.SHARE,
                    teamNames.getOrDefault(share.ownerTeamId(), "?"),
                    share.pattern().text(),
                    (int) members.countByTeamId(share.targetTeamId())));
        }
        grants.sort(Comparator.comparing((ResourceTeamGrant g) -> g.source())
                .thenComparing(ResourceTeamGrant::teamName)
                .thenComparing(ResourceTeamGrant::roleName));
        return new ResourceAccessView(owner, List.copyOf(grants));
    }
}

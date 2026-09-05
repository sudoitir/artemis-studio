package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.persist.ApiTokenGrantRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.persist.EnvironmentEntity;
import io.github.sudoitir.artemisstudio.persist.EnvironmentRepository;
import io.github.sudoitir.artemisstudio.persist.OidcRoleMappingRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ClusterEnvironmentIndex;
import io.github.sudoitir.artemisstudio.web.dto.EnvironmentViews.EnvironmentRequest;
import io.github.sudoitir.artemisstudio.web.dto.EnvironmentViews.EnvironmentView;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Environment CRUD and cluster assignment (environments spec). Removing an
 * environment sets member clusters' {@code environment_id} to {@code NULL}
 * (the FK's own {@code ON DELETE SET NULL}) and explicitly drops any
 * {@code ENVIRONMENT}-scoped grant or OIDC mapping pointed at it, since those
 * are ordinary rows with no FK to cascade through.
 */
@Service
@RequiredArgsConstructor
public class EnvironmentService {

    private final EnvironmentRepository environments;
    private final ClusterRepository clusters;
    private final UserRoleRepository userRoles;
    private final ApiTokenGrantRepository apiTokenGrants;
    private final OidcRoleMappingRepository oidcMappings;
    private final ClusterEnvironmentIndex environmentIndex;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ENVIRONMENT_READ)")
    @Transactional(readOnly = true)
    public List<EnvironmentView> list() {
        return environments.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(this::toView)
                .toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ENVIRONMENT_WRITE)")
    @Transactional
    public EnvironmentView create(EnvironmentRequest request) {
        if (environments.existsByName(request.name())) {
            throw new ConflictException(
                    "duplicate-environment-name", "An environment named '" + request.name() + "' already exists.");
        }
        return toView(environments.save(new EnvironmentEntity(request.name(), request.colour(), request.sortOrder())));
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ENVIRONMENT_WRITE)")
    @Transactional
    public EnvironmentView update(UUID environmentId, EnvironmentRequest request) {
        EnvironmentEntity env = require(environmentId);
        env.setName(request.name());
        env.setColour(request.colour());
        env.setSortOrder(request.sortOrder());
        return toView(environments.save(env));
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ENVIRONMENT_WRITE)")
    @Transactional
    public void delete(UUID environmentId) {
        require(environmentId);
        environments.deleteById(environmentId); // cascades cluster.environment_id -> NULL (ON DELETE SET NULL)
        userRoles.deleteByIdScopeTypeAndIdScopeId("ENVIRONMENT", environmentId);
        apiTokenGrants.deleteByIdScopeTypeAndIdScopeId("ENVIRONMENT", environmentId);
        oidcMappings.findAllByOrderByClaimAscClaimValueAsc().stream()
                .filter(m -> "ENVIRONMENT".equals(m.getScopeType()) && environmentId.equals(m.getScopeId()))
                .forEach(m -> oidcMappings.deleteById(m.getId()));
        environmentIndex.invalidate();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).CLUSTER_WRITE)")
    @Transactional
    public void assignCluster(UUID clusterId, UUID environmentId) {
        ClusterEntity cluster =
                clusters.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        if (environmentId != null) {
            require(environmentId);
        }
        cluster.setEnvironmentId(environmentId);
        clusters.save(cluster);
        environmentIndex.invalidate();
    }

    private EnvironmentEntity require(UUID environmentId) {
        return environments
                .findById(environmentId)
                .orElseThrow(() -> new NotFoundException("environment", environmentId));
    }

    private EnvironmentView toView(EnvironmentEntity env) {
        return new EnvironmentView(env.getId(), env.getName(), env.getColour(), env.getSortOrder());
    }
}

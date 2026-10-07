package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.gate.Gated;
import io.github.sudoitir.artemisstudio.kernel.gate.Operation;
import io.github.sudoitir.artemisstudio.kernel.gate.OperationGate;
import io.github.sudoitir.artemisstudio.kernel.security.ScopedGrants;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.EnvironmentEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.EnvironmentRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.web.EnvironmentViews.EnvironmentRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.EnvironmentViews.EnvironmentView;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final ScopedGrants grants;
    private final ClusterEnvironmentIndex environmentIndex;
    private final OperationGate gate;
    private final TransactionTemplate transactions;

    /** What the gate holds of an environment create. */
    record EnvironmentCreateParams(String name, String colour, int sortOrder) {}

    /** What the gate holds of an environment edit. */
    record EnvironmentUpdateParams(UUID environmentId, String name, String colour, int sortOrder) {}

    /** What the gate holds of an environment delete. */
    record EnvironmentDeleteParams(UUID environmentId) {}

    /** What the gate holds of moving a cluster into an environment, or out of its one when {@code environmentId} is null. */
    record ClusterAssignParams(UUID clusterId, UUID environmentId) {}

    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.clusters.ClusterPermissions).ENVIRONMENT_READ)")
    @Transactional(readOnly = true)
    public List<EnvironmentView> list() {
        return environments.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(this::toView)
                .toList();
    }

    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.clusters.ClusterPermissions).ENVIRONMENT_WRITE)")
    public EnvironmentView create(EnvironmentRequest request) {
        requireNameFree(request.name());
        return gatedCreate(new EnvironmentCreateParams(request.name(), request.colour(), request.sortOrder()));
    }

    @Gated("environment.create")
    private EnvironmentView gatedCreate(EnvironmentCreateParams p) {
        return gate.run(
                Operation.of(p),
                () -> transactions.execute(status -> {
                    requireNameFree(p.name());
                    return toView(environments.save(new EnvironmentEntity(p.name(), p.colour(), p.sortOrder())));
                }));
    }

    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.clusters.ClusterPermissions).ENVIRONMENT_WRITE)")
    public EnvironmentView update(UUID environmentId, EnvironmentRequest request) {
        require(environmentId);
        return gatedUpdate(
                new EnvironmentUpdateParams(environmentId, request.name(), request.colour(), request.sortOrder()));
    }

    @Gated("environment.update")
    private EnvironmentView gatedUpdate(EnvironmentUpdateParams p) {
        return gate.run(
                Operation.of(p),
                () -> transactions.execute(status -> {
                    EnvironmentEntity env = require(p.environmentId());
                    env.setName(p.name());
                    env.setColour(p.colour());
                    env.setSortOrder(p.sortOrder());
                    return toView(environments.save(env));
                }));
    }

    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.clusters.ClusterPermissions).ENVIRONMENT_WRITE)")
    public void delete(UUID environmentId) {
        require(environmentId);
        gatedDelete(new EnvironmentDeleteParams(environmentId));
    }

    @Gated("environment.delete")
    private void gatedDelete(EnvironmentDeleteParams p) {
        gate.run(Operation.of(p), () -> {
            transactions.executeWithoutResult(status -> {
                require(p.environmentId());
                environments.deleteById(
                        p.environmentId()); // cascades cluster.environment_id -> NULL (ON DELETE SET NULL)
                grants.revoke("ENVIRONMENT", p.environmentId());
                environmentIndex.invalidate();
            });
            return null;
        });
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.platform.clusters.ClusterPermissions).CLUSTER_WRITE)")
    public void assignCluster(UUID clusterId, UUID environmentId) {
        clusters.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        if (environmentId != null) {
            require(environmentId);
        }
        gatedAssign(new ClusterAssignParams(clusterId, environmentId));
    }

    @Gated("cluster.assign-environment")
    private void gatedAssign(ClusterAssignParams p) {
        gate.run(Operation.of(p), () -> {
            transactions.executeWithoutResult(status -> {
                ClusterEntity cluster = clusters.findById(p.clusterId())
                        .orElseThrow(() -> new NotFoundException("cluster", p.clusterId()));
                if (p.environmentId() != null) {
                    require(p.environmentId());
                }
                cluster.setEnvironmentId(p.environmentId());
                clusters.save(cluster);
                environmentIndex.invalidate();
            });
            return null;
        });
    }

    /** The clusters in an environment, which deleting it takes out of it. */
    long memberCount(UUID environmentId) {
        require(environmentId);
        return clusters.findAllByOrderByNameAsc().stream()
                .filter(c -> environmentId.equals(c.getEnvironmentId()))
                .count();
    }

    /** The environment's name for a sentence an operator reads, or its id when it is gone. */
    String labelOf(UUID environmentId) {
        return environmentId == null
                ? "no environment"
                : environments
                        .findById(environmentId)
                        .map(EnvironmentEntity::getName)
                        .orElse(String.valueOf(environmentId));
    }

    private void requireNameFree(String name) {
        if (environments.existsByName(name)) {
            throw new ConflictException(
                    "duplicate-environment-name", "An environment named '" + name + "' already exists.");
        }
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

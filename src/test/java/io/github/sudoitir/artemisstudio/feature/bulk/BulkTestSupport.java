package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkPreviewRequest;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerSettings;
import io.github.sudoitir.artemisstudio.platform.broker.QueueRow;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.support.OperatorFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

/** A cluster of two live nodes, a signed-in operator with a real account, and queues seeded into the snapshot. */
abstract class BulkTestSupport extends PostgresIntegrationTest {

    @Autowired
    BulkService bulk;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    SettingsService settings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    GrantLoader grants;

    UUID clusterId;
    UUID nodeA;
    UUID nodeB;
    UUID userId;

    @BeforeEach
    void cluster() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        nodeA = node("a");
        nodeB = node("b");
        userId = OperatorFixture.signIn(users, roles, rolePermissions, userRoles, grants);
    }

    @AfterEach
    void cleanUp() {
        settings.reset(BrokerSettings.BULK_QUEUE_CAP);
        settings.reset(BrokerSettings.BULK_CAP);
        clusters.deleteById(clusterId);
        SecurityContextHolder.clearContext();
    }

    private UUID node(String name) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachManagementUrl("http://" + name + ":8161/console/jolokia");
        n.applyHaState(true, "STARTED", "PRIMARY", null, 1L, "2.44.0", null, Instant.now());
        return nodes.save(n).getId();
    }

    void queue(UUID node, String name, long messages, long consumers, boolean paused) {
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId, node, name, name, "ANYCAST", true, messages, consumers, 0, 0, 0, 0, 0, paused)));
    }

    /** The node stops answering: its last snapshot is older than the freshness window. */
    void stale(UUID node) {
        jdbc.update("UPDATE queue_snapshot SET ts = now() - interval '1 day' WHERE node_id = ?", node);
    }

    BulkRunDetailView preview(BulkOperation operation, String q) {
        return bulk.preview(clusterId, new BulkPreviewRequest(operation, null, q, false));
    }

    BulkRunDetailView preview(BulkOperation operation, List<String> names) {
        return bulk.preview(clusterId, new BulkPreviewRequest(operation, names, null, false));
    }
}

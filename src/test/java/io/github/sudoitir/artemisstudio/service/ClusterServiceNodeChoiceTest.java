package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which node capabilities are assessed against. A passive backup answers
 * management reads but registers no acceptor and no address MBeans, so probing
 * one reports "CORE acceptor not found" and "activemq.notifications address not
 * found" about a broker where both are present.
 */
class ClusterServiceNodeChoiceTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static BrokerNodeEntity node(String name, Boolean active, String error) {
        BrokerNodeEntity n =
                BrokerNodeEntity.fromSeed(CLUSTER, name, active == Boolean.TRUE ? "PRIMARY" : "BACKUP", null);
        n.attachManagementUrl("http://" + name + "/console/jolokia");
        n.applyHaState(active, "STARTED", n.getHaRole(), true, 1L, "2.39.0", null, Instant.now());
        if (error != null) {
            n.recordError(Instant.now(), error);
        }
        return n;
    }

    @Test
    void prefersALiveNodeOverAnAlphabeticallyEarlierBackup() {
        // The shape that caused the false report: the backup sorts first by name.
        List<BrokerNodeEntity> nodes =
                List.of(node("broker-1-backup:61511", false, null), node("broker-1-primary:61510", true, null));

        assertThat(ClusterService.chooseManageable(nodes))
                .map(BrokerNodeEntity::getName)
                .contains("broker-1-primary:61510");
    }

    @Test
    void skipsALiveNodeThatIsCurrentlyErroring() {
        List<BrokerNodeEntity> nodes = List.of(node("a:1", true, "connect timed out"), node("b:2", true, null));

        assertThat(ClusterService.chooseManageable(nodes))
                .map(BrokerNodeEntity::getName)
                .contains("b:2");
    }

    @Test
    void fallsBackToAnyManageableNodeWhenNoneIsLive() {
        List<BrokerNodeEntity> nodes = List.of(node("a:1", false, null), node("b:2", false, null));

        assertThat(ClusterService.chooseManageable(nodes))
                .map(BrokerNodeEntity::getName)
                .contains("a:1");
    }

    @Test
    void aNodeWithoutAManagementUrlIsNeverChosen() {
        BrokerNodeEntity unmanaged = BrokerNodeEntity.fromSeed(CLUSTER, "discovered-only", "PRIMARY", null);

        assertThat(ClusterService.chooseManageable(List.of(unmanaged))).isEmpty();
    }
}

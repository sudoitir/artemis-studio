package io.github.sudoitir.artemisstudio.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.StudioVersion;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

class StudioInfoTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final PermissionResolver perm = mock(PermissionResolver.class);

    @SuppressWarnings("unchecked")
    private StudioInfo info(String version) {
        ObjectProvider<BuildProperties> noBuildInfo = mock(ObjectProvider.class);
        when(noBuildInfo.getIfAvailable()).thenReturn(null);
        var studioVersion =
                new StudioVersion(noBuildInfo, new PluginProperties(version, false, null, null, null, null, null));
        return new StudioInfo(studioVersion, clusters, perm);
    }

    @Test
    void givesTheReleasedVersion() {
        assertThat(info("2026.10.1").version()).contains("2026.10.1");
    }

    @Test
    void aDevelopmentBuildHasNoVersion() {
        assertThat(info(null).version()).isEmpty();
    }

    @Test
    void countsEveryBrokerNodeOfEveryClusterWithoutNamingAny() {
        when(clusters.allNodes())
                .thenReturn(List.of(
                        mock(ClusterNode.class),
                        mock(ClusterNode.class),
                        mock(ClusterNode.class),
                        mock(ClusterNode.class),
                        mock(ClusterNode.class)));

        assertThat(info(null).brokerInstances()).isEqualTo(5);
    }

    @Test
    void anInstallationWithNoBrokersHasNone() {
        when(clusters.allNodes()).thenReturn(List.of());

        assertThat(info(null).brokerInstances()).isZero();
    }

    @Test
    void namesAClusterTheCallerCanSee() {
        RegisteredCluster cluster = mock(RegisteredCluster.class);
        when(cluster.getName()).thenReturn("payments");
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.of(cluster));
        when(perm.canSeeCluster(CLUSTER)).thenReturn(true);

        assertThat(info(null).clusterName(CLUSTER)).contains("payments");
    }

    @Test
    void aHiddenClusterAnswersLikeAMissingOne() {
        RegisteredCluster cluster = mock(RegisteredCluster.class);
        when(cluster.getName()).thenReturn("payments");
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.of(cluster));
        when(perm.canSeeCluster(CLUSTER)).thenReturn(false);

        assertThat(info(null).clusterName(CLUSTER)).isEmpty();
        assertThat(info(null).clusterName(UUID.randomUUID())).isEmpty();
    }

    @Test
    void listsOnlyTheClustersTheCallerCanSeeByName() {
        UUID hidden = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<RegisteredCluster> all =
                List.of(cluster(CLUSTER, "payments"), cluster(hidden, "audit"), cluster(other, "Orders"));
        when(clusters.clusters()).thenReturn(all);
        when(perm.canSeeCluster(CLUSTER)).thenReturn(true);
        when(perm.canSeeCluster(other)).thenReturn(true);

        assertThat(info(null).clusters()).containsExactly(Map.entry(other, "Orders"), Map.entry(CLUSTER, "payments"));
    }

    private static RegisteredCluster cluster(UUID id, String name) {
        RegisteredCluster cluster = mock(RegisteredCluster.class);
        when(cluster.getId()).thenReturn(id);
        when(cluster.getName()).thenReturn(name);
        return cluster;
    }
}

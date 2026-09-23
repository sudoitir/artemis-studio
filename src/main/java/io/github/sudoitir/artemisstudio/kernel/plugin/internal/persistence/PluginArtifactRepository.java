package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** {@code plugin_artifact} access. Listing queries never select {@code content}. */
public interface PluginArtifactRepository extends JpaRepository<PluginArtifactEntity, String> {

    @Query("select a.sha256 from PluginArtifactEntity a")
    List<String> findAllSha256();

    /**
     * Every stored sha256 that is not referenced as a plugin's current or previous artifact —
     * what {@code PluginStore#garbageCollect} may delete.
     */
    @Query("""
            select a.sha256 from PluginArtifactEntity a
            where a.sha256 not in (select i.sha256 from PluginInstallEntity i)
            and a.sha256 not in (select i.previousSha256 from PluginInstallEntity i where i.previousSha256 is not null)
            """)
    List<String> findUnreferencedSha256();
}

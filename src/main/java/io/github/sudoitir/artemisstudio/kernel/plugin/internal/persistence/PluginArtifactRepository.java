package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** {@code plugin_artifact} access. Listing queries never select {@code content}. */
public interface PluginArtifactRepository extends JpaRepository<PluginArtifactEntity, String> {

    @Query("select a.sha256 from PluginArtifactEntity a")
    List<String> findAllSha256();

    /**
     * Every stored sha256 that is not referenced as a plugin's current or previous artifact, and
     * not held as an inspected-but-not-yet-activated upload ({@code plugin_upload}) — what {@code
     * PluginStore#garbageCollect} may delete. Native SQL because {@code plugin_upload} has no JPA
     * entity of its own (task 6.6 owns that upload flow; the GC only needs to not delete under it).
     */
    @Query(value = """
                    select a.sha256 from plugin_artifact a
                    where a.sha256 not in (select i.sha256 from plugin_install i)
                    and a.sha256 not in (select i.previous_sha256 from plugin_install i where i.previous_sha256 is not null)
                    and a.sha256 not in (select u.sha256 from plugin_upload u)
                    """, nativeQuery = true)
    List<String> findUnreferencedSha256();
}

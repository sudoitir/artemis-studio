package io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** {@code plugin_license} access, keyed by plugin id. */
public interface PluginLicenseRepository extends JpaRepository<PluginLicenseEntity, String> {

    /**
     * Records a verdict for the stored file, and only if its hash is still {@code sha256}: one
     * statement, so a late report can never land on a file an admin has replaced since.
     *
     * @return 1 when recorded, 0 when there is no file or it is no longer that one
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE PluginLicenseEntity l
               SET l.status = :status, l.expiresAt = :expiresAt, l.licensee = :licensee,
                   l.detail = :detail, l.reportedAt = :reportedAt
             WHERE l.pluginId = :pluginId AND l.sha256 = :sha256
            """)
    int report(
            @Param("pluginId") String pluginId,
            @Param("sha256") String sha256,
            @Param("status") String status,
            @Param("expiresAt") Instant expiresAt,
            @Param("licensee") String licensee,
            @Param("detail") String detail,
            @Param("reportedAt") Instant reportedAt);

    @Transactional
    long deleteByPluginId(String pluginId);
}

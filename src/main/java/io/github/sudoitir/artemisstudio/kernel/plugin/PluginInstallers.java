package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The installer tier (ADR-0103): who may install, update and remove plugins. Deliberately not a
 * role permission, so no role an administrator can edit confers it, and read on every request
 * rather than cached in a session, so a revocation takes effect on the caller's next request.
 */
@Service
@RequiredArgsConstructor
public class PluginInstallers {

    public record Installer(UUID userId, Instant grantedAt, String grantedBy) {}

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public boolean isInstaller(UUID userId) {
        return userId != null
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM plugin_installer WHERE user_id = ?)", Boolean.class, userId));
    }

    @Transactional(readOnly = true)
    public List<Installer> list() {
        return jdbc.query(
                "SELECT user_id, granted_at, granted_by FROM plugin_installer ORDER BY granted_at",
                (rs, n) -> new Installer(
                        rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3)));
    }

    @Transactional(readOnly = true)
    public boolean none() {
        return Boolean.FALSE.equals(
                jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM plugin_installer)", Boolean.class));
    }

    /** Idempotent. */
    @Transactional
    public void grant(UUID userId, String grantedBy) {
        jdbc.update(
                "INSERT INTO plugin_installer (granted_at, granted_by, user_id) VALUES (now(), ?, ?)"
                        + " ON CONFLICT (user_id) DO NOTHING",
                grantedBy,
                userId);
    }

    /**
     * Revokes, but never the last installer: with none left, only a configuration change and a
     * restart could install a plugin again.
     *
     * @return false when {@code userId} is the last installer and nothing was revoked
     */
    @Transactional
    public boolean revoke(UUID userId) {
        // The row lock serialises two concurrent revocations, so they cannot both see two rows.
        List<UUID> all = jdbc.queryForList("SELECT user_id FROM plugin_installer FOR UPDATE", UUID.class);
        if (!all.contains(userId)) {
            return true;
        }
        if (all.size() == 1) {
            return false;
        }
        jdbc.update("DELETE FROM plugin_installer WHERE user_id = ?", userId);
        return true;
    }
}

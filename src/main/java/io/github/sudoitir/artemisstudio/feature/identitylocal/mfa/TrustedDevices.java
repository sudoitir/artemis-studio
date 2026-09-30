package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.feature.identitylocal.IdentityLocalSettings;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The browsers a user trusted after a second factor (ADR-0142), in {@code local_trusted_device}. The
 * cookie holds 256 random bits and only their SHA-256 is stored, so a copy of the table signs nobody in
 * and a plain hash is enough. A row counts until the earlier of its own expiry and its creation plus the
 * lifetime setting as it is now, so lowering the setting shortens every device at once and {@code 0}
 * turns them all off. Every change is audited; the callers own the transactions.
 */
@Component
@RequiredArgsConstructor
class TrustedDevices {

    private static final int TOKEN_BYTES = 32;

    /** A stored device; {@code expiresAt} is what the row says, not what the setting now allows. */
    record Device(
            UUID id,
            String userAgent,
            String clientAddress,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            byte[] tokenHash) {}

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final AuditService audit;
    private final ActorResolver actors;
    private final UserAccounts accounts;
    private final SecureRandom random = new SecureRandom();

    /** How long a device stays trusted; zero when trusted devices are off. */
    Duration lifetime() {
        return settings.duration(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME);
    }

    /** Trust a browser. Returns the token for its cookie, which is never stored. */
    String create(UUID userId, String clientAddress, String userAgent) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(lifetime());
        jdbc.sql("DELETE FROM local_trusted_device WHERE user_id = :id AND expires_at <= now()")
                .param("id", userId)
                .update();
        jdbc.sql("""
                INSERT INTO local_trusted_device (expires_at, user_agent, client_address, token_hash, user_id)
                VALUES (:expires, :agent, :address, :hash, :id)
                """)
                .param("expires", java.sql.Timestamp.from(expiresAt))
                .param("agent", userAgent)
                .param("address", clientAddress)
                .param("hash", hash(token))
                .param("id", userId)
                .update();
        audited(userId, "TRUSTED_DEVICE_ADD", Map.of("expiresAt", expiresAt.toString()));
        return token;
    }

    /**
     * Whether {@code token} is a live trusted device of this user, and if so record that it was used. Every
     * one of the user's devices is compared, in constant time, so the answer does not depend on which matched.
     */
    boolean use(UUID userId, String token) {
        Duration lifetime = lifetime();
        if (lifetime.isZero()) {
            return false;
        }
        byte[] wanted = hash(token);
        Instant now = Instant.now();
        Device matched = null;
        for (Device device : all(userId)) {
            if (MessageDigest.isEqual(device.tokenHash(), wanted)) {
                matched = device;
            }
        }
        if (matched == null || !now.isBefore(effectiveExpiry(matched, lifetime))) {
            return false;
        }
        jdbc.sql("UPDATE local_trusted_device SET last_used_at = now() WHERE id = :id")
                .param("id", matched.id())
                .update();
        return true;
    }

    /** The user's devices that still count, newest first. */
    List<Device> live(UUID userId) {
        Duration lifetime = lifetime();
        Instant now = Instant.now();
        return all(userId).stream()
                .filter(d -> now.isBefore(effectiveExpiry(d, lifetime)))
                .sorted(java.util.Comparator.comparing(Device::createdAt).reversed())
                .toList();
    }

    /** When the device stops counting: its own expiry, or its creation plus the lifetime now in force, if that is sooner. */
    static Instant effectiveExpiry(Device device, Duration lifetime) {
        Instant byLifetime = device.createdAt().plus(lifetime);
        return byLifetime.isBefore(device.expiresAt()) ? byLifetime : device.expiresAt();
    }

    /** Whether {@code token} is this device's (in constant time). */
    static boolean isCurrent(Device device, String token) {
        return token != null && MessageDigest.isEqual(device.tokenHash(), hash(token));
    }

    /** Revoke one of the user's devices; false when they have no such device. */
    boolean revoke(UUID userId, UUID deviceId) {
        boolean revoked = jdbc.sql("DELETE FROM local_trusted_device WHERE id = :id AND user_id = :user")
                        .param("id", deviceId)
                        .param("user", userId)
                        .update()
                == 1;
        if (revoked) {
            audited(userId, "TRUSTED_DEVICE_REVOKE", Map.of("count", 1, "reason", "revoked by the user"));
        }
        return revoked;
    }

    /** Revoke every device of the user, saying why; audited when there was any. */
    int revokeAll(UUID userId, String reason) {
        int revoked = jdbc.sql("DELETE FROM local_trusted_device WHERE user_id = :id")
                .param("id", userId)
                .update();
        if (revoked > 0) {
            audited(userId, "TRUSTED_DEVICE_REVOKE", Map.of("count", revoked, "reason", reason));
        }
        return revoked;
    }

    private List<Device> all(UUID userId) {
        return jdbc.sql("""
                SELECT id, user_agent, client_address, created_at, last_used_at, expires_at, token_hash
                FROM local_trusted_device WHERE user_id = :id
                """)
                .param("id", userId)
                .query((rs, row) -> new Device(
                        rs.getObject("id", UUID.class),
                        rs.getString("user_agent"),
                        rs.getString("client_address"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getBytes("token_hash")))
                .list();
    }

    private void audited(UUID userId, String action, Map<String, ?> params) {
        String username =
                accounts.byId(userId).map(UserAccounts.Account::username).orElse(userId.toString());
        audit.succeed(audit.begin(actors.resolve(), action, "user", username, null, null, params, false), 1);
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

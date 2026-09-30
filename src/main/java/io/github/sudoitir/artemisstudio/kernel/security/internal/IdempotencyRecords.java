package io.github.sudoitir.artemisstudio.kernel.security.internal;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The {@code idempotency_record} rows behind {@link IdempotencyFilter} (ADR-0150): claim, complete, release, find. */
@Component
@RequiredArgsConstructor
class IdempotencyRecords {

    /** A claim not completed within this long was abandoned (a crash, a lost connection) and can be claimed again. */
    static final int LEASE_SECONDS = 600;

    /** A key is forgotten after this long, whatever the data lifecycle still holds (ADR-0150). */
    static final int TTL_SECONDS = 24 * 3600;

    /** {@code status}, {@code contentType}, {@code headers} and {@code body} are null until the request is done. */
    record Stored(String fingerprint, boolean done, Integer status, String contentType, String headers, byte[] body) {}

    private final JdbcClient jdbc;

    /**
     * Takes the key as PENDING; false when the user holds a live row for it. A row is live while it is DONE and
     * younger than the TTL, or PENDING and younger than the lease; any other row is overwritten, atomically.
     */
    boolean claim(UUID userId, String key, String fingerprint) {
        return jdbc.sql("""
                        INSERT INTO idempotency_record (user_id, idem_key, fingerprint, state)
                        VALUES (?, ?, ?, 'PENDING')
                        ON CONFLICT (user_id, idem_key) DO UPDATE SET
                            fingerprint = EXCLUDED.fingerprint, state = 'PENDING', created_at = now(),
                            status = NULL, content_type = NULL, headers = NULL, body = NULL
                        WHERE idempotency_record.created_at < now() - make_interval(secs => ?)
                           OR (idempotency_record.state = 'PENDING'
                               AND idempotency_record.created_at < now() - make_interval(secs => ?))""")
                        .params(userId, key, fingerprint, TTL_SECONDS, LEASE_SECONDS)
                        .update()
                == 1;
    }

    /** The live row for the key, if any: an expired one is absent. */
    Optional<Stored> find(UUID userId, String key) {
        return jdbc.sql("""
                        SELECT fingerprint, state, status, content_type, headers, body
                        FROM idempotency_record
                        WHERE user_id = ? AND idem_key = ? AND created_at >= now() - make_interval(secs => ?)""")
                .params(userId, key, TTL_SECONDS)
                .query((rs, i) -> new Stored(
                        rs.getString(1),
                        "DONE".equals(rs.getString(2)),
                        (Integer) rs.getObject(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getBytes(6)))
                .optional();
    }

    void complete(UUID userId, String key, int status, String contentType, String headers, byte[] body) {
        jdbc.sql("""
                        UPDATE idempotency_record SET state = 'DONE', status = ?, content_type = ?, headers = ?, body = ?
                        WHERE user_id = ? AND idem_key = ?""").params(status, contentType, headers, body, userId, key).update();
    }

    void release(UUID userId, String key) {
        jdbc.sql("DELETE FROM idempotency_record WHERE user_id = ? AND idem_key = ?")
                .params(userId, key)
                .update();
    }
}

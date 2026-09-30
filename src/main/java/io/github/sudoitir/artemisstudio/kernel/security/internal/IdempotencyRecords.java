package io.github.sudoitir.artemisstudio.kernel.security.internal;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The {@code idempotency_record} rows behind {@link IdempotencyFilter} (ADR-0148): claim, complete, release, find. */
@Component
@RequiredArgsConstructor
class IdempotencyRecords {

    /** {@code status}, {@code contentType} and {@code body} are null until the request is done. */
    record Stored(String fingerprint, boolean done, Integer status, String contentType, byte[] body) {}

    private final JdbcClient jdbc;

    /** Takes the key as PENDING; false when the user already has a row for it. */
    boolean claim(UUID userId, String key, String fingerprint) {
        return jdbc.sql("""
                        INSERT INTO idempotency_record (user_id, idem_key, fingerprint, state)
                        VALUES (?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING""").params(userId, key, fingerprint).update() == 1;
    }

    Optional<Stored> find(UUID userId, String key) {
        return jdbc.sql("""
                        SELECT fingerprint, state, status, content_type, body
                        FROM idempotency_record WHERE user_id = ? AND idem_key = ?""")
                .params(userId, key)
                .query((rs, i) -> new Stored(
                        rs.getString(1),
                        "DONE".equals(rs.getString(2)),
                        (Integer) rs.getObject(3),
                        rs.getString(4),
                        rs.getBytes(5)))
                .optional();
    }

    void complete(UUID userId, String key, int status, String contentType, byte[] body) {
        jdbc.sql("""
                        UPDATE idempotency_record SET state = 'DONE', status = ?, content_type = ?, body = ?
                        WHERE user_id = ? AND idem_key = ?""").params(status, contentType, body, userId, key).update();
    }

    void release(UUID userId, String key) {
        jdbc.sql("DELETE FROM idempotency_record WHERE user_id = ? AND idem_key = ?")
                .params(userId, key)
                .update();
    }
}

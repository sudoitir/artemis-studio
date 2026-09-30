package io.github.sudoitir.artemisstudio.kernel.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one KEK version every replica wraps new secrets with (stored, so restart order does not matter): the single
 * row of {@code secret_key_state} (kernel/security changeset 0003).
 */
@Component
@RequiredArgsConstructor
public class SecretKeyState {

    private final JdbcTemplate jdbc;

    /** The stored current version. When none is stored yet, {@code initial} is stored first. */
    public int currentOrInit(int initial) {
        jdbc.update(
                "INSERT INTO secret_key_state (current_kek_version, updated_at) VALUES (?, now())"
                        + " ON CONFLICT DO NOTHING",
                initial);
        Integer current = jdbc.queryForObject("SELECT current_kek_version FROM secret_key_state", Integer.class);
        return current == null ? initial : current;
    }
}

package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.SecretKeyState;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code secret_key_state} (kernel/security changeset 0003): a single row. */
@Component
@RequiredArgsConstructor
class JdbcSecretKeyState implements SecretKeyState {

    private final JdbcTemplate jdbc;

    @Override
    public int currentOrInit(int initial, String provider) {
        jdbc.update(
                "INSERT INTO secret_key_state (current_kek_version, provider, updated_at) VALUES (?, ?, now())"
                        + " ON CONFLICT DO NOTHING",
                initial,
                provider);
        Integer current = jdbc.queryForObject("SELECT current_kek_version FROM secret_key_state", Integer.class);
        return current == null ? initial : current;
    }
}

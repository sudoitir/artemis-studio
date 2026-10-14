package io.github.sudoitir.rahdar.kernel.settings;

import io.github.sudoitir.rahdar.kernel.settings.internal.persistence.RahdarSettingEntity;
import io.github.sudoitir.rahdar.kernel.settings.internal.persistence.RahdarSettingRepository;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * This Rahdar's identity, as it appears in the names of the broker objects it owns
 * (ADR-0062 D5).
 *
 * <p>It has to be stable across restarts, and that is not a nicety. A capture divert
 * outlives the broker's restart and Rahdar's own (ADR-0065), so an id minted at boot
 * would leave every tap from the previous run unrecognisable — not destroyed, not
 * re-used, simply orphaned on the broker with nothing left that admits to owning it.
 * So it is generated once and kept in {@code rahdar_setting}, beside the database that
 * holds the subscriptions it is paired with.
 *
 * <p>It is short on purpose: it appears in an object name an operator reads in the
 * broker's own tooling, and a full UUID there is noise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RahdarInstance {

    /** Not an operator-tunable setting, which is why it is not in the settings registry. */
    public static final String SETTING_KEY = "capture.instance-id";

    private final RahdarSettingRepository settings;

    private volatile String id;

    @PostConstruct
    public void resolve() {
        id = settings.findById(SETTING_KEY)
                .map(RahdarSettingEntity::getValue)
                .map(RahdarInstance::unquote)
                .orElseGet(this::mint);
    }

    /** Eight hex characters, stable for the life of this Rahdar's database. */
    public String id() {
        if (id == null) {
            resolve();
        }
        return id;
    }

    private String mint() {
        String minted = UUID.randomUUID().toString().substring(0, 8);
        // Replicas booting together race here; the first insert wins and all of them read it back.
        if (settings.insertIfAbsent(SETTING_KEY, '"' + minted + '"') == 1) {
            log.info("This Rahdar instance is '{}'; capture objects it creates carry that name", minted);
        }
        return settings.findById(SETTING_KEY)
                .map(RahdarSettingEntity::getValue)
                .map(RahdarInstance::unquote)
                .orElseThrow();
    }

    private static String unquote(String json) {
        String trimmed = json == null ? "" : json.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }
}

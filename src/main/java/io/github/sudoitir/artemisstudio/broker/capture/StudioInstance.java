package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.persist.StudioSettingEntity;
import io.github.sudoitir.artemisstudio.persist.StudioSettingRepository;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * This Studio's identity, as it appears in the names of the broker objects it owns
 * (ADR-0062 D5).
 *
 * <p>It has to be stable across restarts, and that is not a nicety. A capture divert
 * outlives the broker's restart and Studio's own (ADR-0065), so an id minted at boot
 * would leave every tap from the previous run unrecognisable — not destroyed, not
 * re-used, simply orphaned on the broker with nothing left that admits to owning it.
 * So it is generated once and kept in {@code studio_setting}, beside the database that
 * holds the subscriptions it is paired with.
 *
 * <p>It is short on purpose: it appears in an object name an operator reads in the
 * broker's own tooling, and a full UUID there is noise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StudioInstance {

    /** Not an operator-tunable setting, which is why it is not in the settings registry. */
    public static final String SETTING_KEY = "capture.instance-id";

    private final StudioSettingRepository settings;

    private volatile String id;

    @PostConstruct
    @Transactional
    public void resolve() {
        id = settings.findById(SETTING_KEY)
                .map(StudioSettingEntity::getValue)
                .map(StudioInstance::unquote)
                .orElseGet(this::mint);
    }

    /** Eight hex characters, stable for the life of this Studio's database. */
    public String id() {
        if (id == null) {
            resolve();
        }
        return id;
    }

    private String mint() {
        String minted = UUID.randomUUID().toString().substring(0, 8);
        settings.save(new StudioSettingEntity(SETTING_KEY, '"' + minted + '"'));
        log.info("This Studio instance is '{}'; capture objects it creates carry that name", minted);
        return minted;
    }

    private static String unquote(String json) {
        String trimmed = json == null ? "" : json.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }
}

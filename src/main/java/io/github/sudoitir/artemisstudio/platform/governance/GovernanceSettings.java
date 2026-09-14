package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import org.springframework.stereotype.Component;

/** How much of a body the content policy inspects (ADR-0075 D2). */
@Component
public class GovernanceSettings implements SettingsContribution {

    public static final String SCAN_LIMIT = "governance.scan-limit";

    /** 256 KiB — the capture body cap, so a captured body is scanned whole by default. */
    static final int DEFAULT_SCAN_LIMIT = 262_144;

    @Override
    public String featureId() {
        return "governance";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(new SettingDef(
                SCAN_LIMIT,
                "Data governance",
                "Body scan limit (bytes)",
                "Bytes of a message body inspected for sensitive data. Anything beyond is withheld from users"
                        + " without clear access.",
                Kind.INT,
                () -> Integer.toString(DEFAULT_SCAN_LIMIT),
                null));
    }
}

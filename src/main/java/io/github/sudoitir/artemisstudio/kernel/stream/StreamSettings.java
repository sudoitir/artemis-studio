package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The stream keep-alive interval (ADR-0018, ADR-0052). A setting because the value
 * that keeps a stream open is a property of whatever proxy sits in front of Studio.
 */
@Component
@RequiredArgsConstructor
public class StreamSettings implements SettingsContribution {

    public static final String HEARTBEAT_INTERVAL = "sse.heartbeat-interval";

    private final SseProperties defaults;

    @Override
    public String featureId() {
        return "stream";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(new SettingDef(
                HEARTBEAT_INTERVAL,
                "Stream",
                "SSE heartbeat interval",
                "Keep-alive comment on GET /api/v1/stream. Lower it if a proxy idles the connection out sooner.",
                Kind.DURATION,
                () -> defaults.heartbeatInterval().toString(),
                null));
    }
}

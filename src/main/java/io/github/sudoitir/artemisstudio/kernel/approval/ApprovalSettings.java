package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** The approval gate's bounds (ADR-0180). They only matter while an approval provider is installed. */
@Component
public class ApprovalSettings implements SettingsContribution {

    public static final String DECIDE_TIMEOUT = "gate.decide-timeout";
    public static final String MAX_HOLD = "gate.max-hold";
    public static final String RUN_WINDOW = "gate.run-window";
    public static final String MAX_OPEN_PER_REQUESTER = "gate.max-open-per-requester";
    public static final String RUN_LEASE = "gate.run-lease";

    /** The shortest time a provider may hold a request for. */
    static final Duration MIN_HOLD = Duration.ofMinutes(1);

    private static final String GROUP = "Approvals";

    @Override
    public String featureId() {
        return ApprovalModule.ID;
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        DECIDE_TIMEOUT,
                        GROUP,
                        "Provider answer timeout",
                        "How long a gated operation waits for the approval provider to allow, hold or deny it. A"
                                + " provider that does not answer in time fails the operation closed.",
                        Kind.DURATION,
                        () -> "3s",
                        null,
                        "1s",
                        "10s"),
                new SettingDef(
                        MAX_HOLD,
                        GROUP,
                        "Longest hold",
                        "The longest a held request may wait for a decision, whatever the provider asks for.",
                        Kind.DURATION,
                        () -> "30d",
                        null,
                        "1m",
                        "365d"),
                new SettingDef(
                        RUN_WINDOW,
                        GROUP,
                        "Run window",
                        "How long an approved request may still run after its approval. A request its requester"
                                + " completes themselves, such as a new API token, must be submitted again within it.",
                        Kind.DURATION,
                        () -> "15m",
                        null,
                        "1m",
                        "7d"),
                new SettingDef(
                        MAX_OPEN_PER_REQUESTER,
                        GROUP,
                        "Open requests per user",
                        "How many requests one user may have waiting or running at once.",
                        Kind.INT,
                        () -> "20",
                        null,
                        "1",
                        "1000"),
                new SettingDef(
                        RUN_LEASE,
                        GROUP,
                        "Run lease",
                        "How long an approved request may run on a Studio instance that has stopped before its"
                                + " outcome is recorded as unknown. It is never run again.",
                        Kind.DURATION,
                        () -> "15m",
                        null,
                        "1m",
                        "24h"));
    }
}

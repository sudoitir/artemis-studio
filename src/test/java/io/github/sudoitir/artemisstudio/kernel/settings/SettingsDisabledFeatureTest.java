package io.github.sudoitir.artemisstudio.kernel.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor.Kind;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDisabledException;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.InstalledFeatures;
import io.github.sudoitir.artemisstudio.kernel.plugin.web.FeatureDisabledAdvice;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.settings.internal.persistence.StudioSettingEntity;
import io.github.sudoitir.artemisstudio.kernel.settings.internal.persistence.StudioSettingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** A disabled feature's settings are neither listed nor writable, and its stored values survive (studio-settings spec). */
class SettingsDisabledFeatureTest {

    private static final String SCRAPE_KEY = "scrape.tier-a-interval";
    private static final String RR_KEY = "rr.sweep-interval";

    private final StudioSettingRepository repo = mock(StudioSettingRepository.class);
    private final AuditService audit = mock(AuditService.class);

    private SettingsService settings(boolean rrEnabled) {
        var env = new MockEnvironment().withProperty("artemis-studio.features.rr.enabled", Boolean.toString(rrEnabled));
        var registry = new FeatureRegistry(
                new InstalledFeatures(List.of(
                        FeatureDescriptor.builder()
                                .id("scrape")
                                .title("Scraping")
                                .kind(Kind.PLATFORM)
                                .required(true)
                                .settingKey(SCRAPE_KEY)
                                .build(),
                        FeatureDescriptor.builder()
                                .id("rr")
                                .title("Request-reply tracing")
                                .kind(Kind.FEATURE)
                                .settingKey(RR_KEY)
                                .build())),
                env,
                event -> {});
        return new SettingsService(
                repo,
                audit,
                mock(ActorResolver.class),
                registry,
                List.of(contribution("scrape", SCRAPE_KEY), contribution("rr", RR_KEY)));
    }

    private static SettingsContribution contribution(String featureId, String key) {
        return new SettingsContribution() {
            @Override
            public String featureId() {
                return featureId;
            }

            @Override
            public List<SettingDef> settings() {
                return List.of(new SettingDef(key, "g", "l", "h", SettingDef.Kind.DURATION, () -> "5s", null));
            }
        };
    }

    @Test
    void aDisabledFeaturesSettingsAreNotListed() {
        assertThat(settings(false).effective()).containsOnlyKeys(SCRAPE_KEY);
        assertThat(settings(true).effective()).containsOnlyKeys(SCRAPE_KEY, RR_KEY);
    }

    @Test
    void writingOrResettingADisabledFeaturesSettingIsRefusedAndNotAudited() {
        SettingsService settings = settings(false);

        assertThatThrownBy(() -> settings.put(RR_KEY, "9s"))
                .isInstanceOf(FeatureDisabledException.class)
                .hasMessageContaining("artemis-studio.features.rr.enabled");
        assertThatThrownBy(() -> settings.reset(RR_KEY)).isInstanceOf(FeatureDisabledException.class);
        verifyNoInteractions(audit);
        verify(repo, never()).deleteById(RR_KEY);
    }

    @Test
    void theRefusalIsA404FeatureDisabledProblem() {
        var e = new FeatureDisabledException(FeatureDescriptor.builder()
                .id("rr")
                .title("Request-reply tracing")
                .kind(Kind.FEATURE)
                .build());

        var problem = new FeatureDisabledAdvice().onFeatureDisabled(e);

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getType().toString()).endsWith("/feature-disabled");
        assertThat(problem.getProperties()).containsEntry("featureId", "rr");
    }

    @Test
    void anUnknownKeyIsStillAnInvalidValue() {
        assertThatThrownBy(() -> settings(false).put("bogus.key", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStoredOverrideOfADisabledFeatureIsKeptAndAppliesWhenReEnabled() {
        when(repo.findAll()).thenReturn(List.of(new StudioSettingEntity(RR_KEY, "\"9s\"")));

        SettingsService disabled = settings(false);
        disabled.applyRuntime();
        verify(repo, never()).deleteById(RR_KEY);

        SettingsService enabled = settings(true);
        enabled.applyRuntime();
        assertThat(enabled.value(RR_KEY)).isEqualTo("9s");
    }
}

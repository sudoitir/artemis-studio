package io.github.sudoitir.artemisstudio.kernel.settings;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import org.junit.jupiter.api.Test;

class SettingBoundsTest {

    private static SettingDef duration(String min, String max) {
        return new SettingDef("lifecycle.x.retention", "g", "l", "h", Kind.DURATION, () -> "7d", null, min, max);
    }

    @Test
    void aDurationOutsideItsBoundsIsRejectedWithTheRange() {
        SettingDef def = duration("1d", "90d");
        assertThatCode(() -> SettingsService.validate(def, "90d")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SettingsService.validate(def, "12h"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lifecycle.x.retention must be between 1d and 90d");
        assertThatThrownBy(() -> SettingsService.validate(def, "91d")).hasMessageContaining("between 1d and 90d");
    }

    @Test
    void foreverIsAcceptedOnlyWhenTheMaximumIsForever() {
        assertThatThrownBy(() -> SettingsService.validate(duration("1d", "90d"), "forever"))
                .hasMessageContaining("between 1d and 90d");
        assertThatCode(() -> SettingsService.validate(duration("30d", "forever"), "forever"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SettingsService.validate(duration("30d", "forever"), "3650d"))
                .doesNotThrowAnyException();
    }

    @Test
    void anIntHonoursItsBounds() {
        SettingDef def = new SettingDef("k", "g", "l", "h", Kind.INT, () -> "80", null, "1", "100");
        assertThatThrownBy(() -> SettingsService.validate(def, "0")).hasMessage("k must be between 1 and 100");
        assertThatThrownBy(() -> SettingsService.validate(def, "101")).hasMessage("k must be between 1 and 100");
        SettingDef quota = new SettingDef("q", "g", "l", "h", Kind.INT, () -> "0", null, "0", null);
        assertThatCode(() -> SettingsService.validate(quota, "0")).doesNotThrowAnyException();
    }
}

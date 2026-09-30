package io.github.sudoitir.artemisstudio.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StudioPropertiesTest {

    @Test
    void blankMeansTheAddressIsNotSet() {
        assertThat(new StudioProperties("").hasPublicUrl()).isFalse();
        assertThat(new StudioProperties("  ").hasPublicUrl()).isFalse();
        assertThat(new StudioProperties(null).hasPublicUrl()).isFalse();
    }

    @Test
    void aTrailingSlashIsDropped() {
        assertThat(new StudioProperties("https://studio.example.com/").publicUrl())
                .isEqualTo("https://studio.example.com");
    }

    @Test
    void aPortIsKept() {
        StudioProperties props = new StudioProperties("http://localhost:8080");

        assertThat(props.hasPublicUrl()).isTrue();
        assertThat(props.publicUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void anAddressWithoutASchemeOrHostIsRefusedAtStartupWithTheSettingNamed() {
        assertThatThrownBy(() -> new StudioProperties("studio.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ARTEMIS_STUDIO_PUBLIC_URL")
                .hasMessageContaining("studio.example.com");
        assertThatThrownBy(() -> new StudioProperties("ftp://studio.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StudioProperties("https://")).isInstanceOf(IllegalArgumentException.class);
    }
}

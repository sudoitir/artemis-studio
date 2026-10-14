package io.github.sudoitir.rahdar.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RahdarPropertiesTest {

    @Test
    void blankMeansTheAddressIsNotSet() {
        assertThat(new RahdarProperties("").hasPublicUrl()).isFalse();
        assertThat(new RahdarProperties("  ").hasPublicUrl()).isFalse();
        assertThat(new RahdarProperties(null).hasPublicUrl()).isFalse();
    }

    @Test
    void aTrailingSlashIsDropped() {
        assertThat(new RahdarProperties("https://rahdar.example.com/").publicUrl())
                .isEqualTo("https://rahdar.example.com");
    }

    @Test
    void aPortIsKept() {
        RahdarProperties props = new RahdarProperties("http://localhost:8080");

        assertThat(props.hasPublicUrl()).isTrue();
        assertThat(props.publicUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void anAddressWithoutASchemeOrHostIsRefusedAtStartupWithTheSettingNamed() {
        assertThatThrownBy(() -> new RahdarProperties("rahdar.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RAHDAR_PUBLIC_URL")
                .hasMessageContaining("rahdar.example.com");
        assertThatThrownBy(() -> new RahdarProperties("ftp://rahdar.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RahdarProperties("https://")).isInstanceOf(IllegalArgumentException.class);
    }
}

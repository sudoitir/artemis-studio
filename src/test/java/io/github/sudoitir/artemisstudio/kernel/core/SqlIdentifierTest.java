package io.github.sudoitir.artemisstudio.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlIdentifierTest {

    @Test
    void aPlainNameIsQuoted() {
        assertThat(SqlIdentifier.quote("audit_event_p202610")).isEqualTo("\"audit_event_p202610\"");
    }

    @Test
    void anythingElseIsRefused() {
        assertThatThrownBy(() -> SqlIdentifier.quote("audit_event; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlIdentifier.quote("a\"b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlIdentifier.quote("Upper")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlIdentifier.quote("1st")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlIdentifier.quote(null)).isInstanceOf(IllegalArgumentException.class);
    }
}

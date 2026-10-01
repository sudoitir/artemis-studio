package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JolokiaRequestTest {

    @Test
    void toStringWithholdsTheArgumentsAnExecCarries() {
        JolokiaRequest send = JolokiaRequest.exec(
                "org.apache.activemq.artemis:broker=\"b\"", "sendMessage", "body", "admin", "s3cret");

        assertThat(send.toString())
                .doesNotContain("s3cret")
                .doesNotContain("admin")
                .contains("3 withheld");
    }
}

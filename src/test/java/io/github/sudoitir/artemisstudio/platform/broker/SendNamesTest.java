package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SendNamesTest {

    @Test
    void validNamesAndHeadersPass() {
        assertThatCode(() -> SendNames.validate(
                        Map.of(
                                "correlationId", "c-1",
                                "type", "order",
                                "replyTo", "replies",
                                "groupId", "g",
                                "groupSeq", 2),
                        Map.of("orderId", "A-1", "_x$1", 1, "JMSXGroupID", "g", "JMSXGroupSeq", 3, "in2", true)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x-long-name", "has space", "1leading", "dot.ted", ""})
    void aNameThatIsNotAJavaIdentifierIsRefused(String name) {
        assertThatThrownBy(() -> SendNames.validate(Map.of(), Map.of(name, "v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid property name '" + name + "'")
                .hasMessageContaining("Java identifier");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "true", "False", "not", "AND", "or", "between", "LIKE", "In", "is", "escape"})
    void aSelectorKeywordIsRefusedInAnyCase(String name) {
        assertThatThrownBy(() -> SendNames.validate(Map.of(), Map.of(name, "v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + name + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"JMSCorrelationID", "JMSType", "JMSXUserID", "JMS_ACTIVEMQ_x", "JMS"})
    void aJmsPrefixedNameIsRefusedExceptTheGroupOnes(String name) {
        assertThatThrownBy(() -> SendNames.validate(Map.of(), Map.of(name, "v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + name + "'")
                .hasMessageContaining("JMSXGroupID and JMSXGroupSeq");
    }

    @Test
    void anUnknownHeaderIsRefusedNamingTheSupportedOnes() {
        assertThatThrownBy(() -> SendNames.validate(Map.of("priority", 4), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported header 'priority'. Supported headers:"
                        + " correlationId, type, replyTo, groupId, groupSeq.");
    }

    @Test
    void aGroupSeqThatIsNotAnIntegerIsRefused() {
        assertThat(SendNames.HEADERS).contains("groupSeq");
        assertThatThrownBy(() -> SendNames.validate(Map.of("groupSeq", "two"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Header 'groupSeq' must be an integer, got 'two'.");
    }
}

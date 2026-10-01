package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SendNamesTest {

    private static final Map<String, Object> NONE = Map.of();

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
        Map<String, Object> properties = Map.of(name, "v");
        assertThatThrownBy(() -> SendNames.validate(NONE, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid property name '" + name + "'")
                .hasMessageContaining("Java identifier");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "true", "False", "not", "AND", "or", "between", "LIKE", "In", "is", "escape"})
    void aSelectorKeywordIsRefusedInAnyCase(String name) {
        Map<String, Object> properties = Map.of(name, "v");
        assertThatThrownBy(() -> SendNames.validate(NONE, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + name + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"JMSCorrelationID", "JMSType", "JMSXUserID", "JMS_ACTIVEMQ_x", "JMS"})
    void aJmsPrefixedNameIsRefusedExceptTheGroupOnes(String name) {
        Map<String, Object> properties = Map.of(name, "v");
        assertThatThrownBy(() -> SendNames.validate(NONE, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + name + "'")
                .hasMessageContaining("JMSXGroupID and JMSXGroupSeq");
    }

    @Test
    void anUnknownHeaderIsRefusedNamingTheSupportedOnes() {
        Map<String, Object> headers = Map.of("priority", 4);
        assertThatThrownBy(() -> SendNames.validate(headers, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported header 'priority'. Supported headers:"
                        + " correlationId, type, replyTo, groupId, groupSeq.");
    }

    @Test
    void aGroupSeqThatIsNotAnIntegerIsRefused() {
        assertThat(SendNames.HEADERS).contains("groupSeq");
        Map<String, Object> headers = Map.of("groupSeq", "two");
        assertThatThrownBy(() -> SendNames.validate(headers, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Header 'groupSeq' must be an integer, got 'two'.");
    }
}

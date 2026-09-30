package io.github.sudoitir.artemisstudio.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecretRedactorTest {

    static Stream<Arguments> cases() {
        String apiToken = "as_" + "A".repeat(11) + "_" + "b".repeat(43);
        return Stream.of(
                Arguments.of("password=hunter2 next", "password=[redacted] next"),
                Arguments.of("Password: hunter2", "Password: [redacted]"),
                Arguments.of("db.password = hunter2, user=bob", "db.password = [redacted], user=bob"),
                Arguments.of(
                        "{\"password\":\"hun ter2\",\"user\":\"bob\"}",
                        "{\"password\":\"[redacted]\",\"user\":\"bob\"}"),
                Arguments.of("api_key='abc def'", "api_key='[redacted]'"),
                Arguments.of("apiKey=abc&x=1", "apiKey=[redacted]&x=1"),
                Arguments.of("Authorization: Bearer abc.def-ghi", "Authorization: [redacted]"),
                Arguments.of("sent Bearer abc.def-ghi now", "sent Bearer [redacted] now"),
                Arguments.of("Authorization: Basic dXNlcjpwYXNz", "Authorization: [redacted]"),
                Arguments.of("header Basic dXNlcjpwYXNz1", "header Basic [redacted]"),
                Arguments.of("tcp://bob:pa55@broker:61616/x", "tcp://[redacted]@broker:61616/x"),
                Arguments.of("https://bob@host/path", "https://[redacted]@host/path"),
                Arguments.of(
                        "key -----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY----- after",
                        "key [redacted] after"),
                Arguments.of("token " + apiToken + " used", "token [redacted] used"),
                Arguments.of("client_secret=abc", "client_secret=[redacted]"),
                // Over-masking is deliberate: a name that merely contains a term is treated as a credential.
                Arguments.of("tokenCount=5", "tokenCount=[redacted]"),
                Arguments.of("password=[redacted]", "password=[redacted]"));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void masksCredentialLikeValues(String input, String expected) {
        assertThat(SecretRedactor.redact(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Message sent to orders.in",
                "Basic settings apply",
                "Invalid password",
                "https://broker:8161/console",
                "the token was refreshed",
                "as_short is fine"
            })
    void leavesOrdinaryTextAlone(String text) {
        assertThat(SecretRedactor.redact(text)).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "DB_PASSWORD",
                "x-api-key",
                "apiKey",
                "client_secret",
                "Authorization",
                "privateKey",
                "userCredentials"
            })
    void recognisesCredentialKeys(String name) {
        assertThat(SecretRedactor.isCredentialKey(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "address", "queue", "user"})
    void ignoresOtherKeys(String name) {
        assertThat(SecretRedactor.isCredentialKey(name)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void passesNullThrough() {
        assertThat(SecretRedactor.redact(null)).isNull();
    }

    /** Adversarial 50 KB inputs; the unbounded patterns took seconds to minutes on these. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "A",
                "token",
                "a.",
                "Zm9vYmFy",
                "password=\"",
                "://",
                "-----BEGIN ",
                "-----BEGIN PRIVATE KEY-----",
                "Bearer  ",
                "Basic "
            })
    void redactsAdversarialInputInLinearTime(String unit) {
        String input = unit.repeat(50_000 / unit.length());

        assertThat(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        java.time.Duration.ofMillis(500), () -> SecretRedactor.redact(input)))
                .isNotNull();
    }
}

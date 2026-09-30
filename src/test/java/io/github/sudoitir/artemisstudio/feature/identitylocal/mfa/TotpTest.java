package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RFC 6238 appendix B, the HMAC-SHA1 vectors. The RFC lists 8-digit codes; checking those proves the
 * core, and the 6-digit code every authenticator app shows is the same number truncated to its last six.
 */
class TotpTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({
        "59, 94287082",
        "1111111109, 07081804",
        "1111111111, 14050471",
        "1234567890, 89005924",
        "2000000000, 69279037",
        "20000000000, 65353130"
    })
    void matchesTheRfcVectors(long seconds, String eightDigits) {
        long step = Totp.stepAt(Instant.ofEpochSecond(seconds));

        assertThat(Totp.code(SECRET, step, 8)).isEqualTo(eightDigits);
        assertThat(Totp.code(SECRET, step, 6)).isEqualTo(eightDigits.substring(2));
    }

    @Test
    void acceptsTheCurrentStepAndOneStepOfDriftEitherWay() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        long step = Totp.stepAt(now);

        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step, 6), now)).hasValue(step);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step - 1, 6), now))
                .hasValue(step - 1);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step + 1, 6), now))
                .hasValue(step + 1);
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step - 2, 6), now))
                .isEmpty();
        assertThat(Totp.matchingStep(SECRET, Totp.code(SECRET, step + 2, 6), now))
                .isEmpty();
    }

    @Test
    void toleratesSpacesAndRejectsAnythingButSixDigits() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        String code = Totp.code(SECRET, Totp.stepAt(now), 6);

        assertThat(Totp.matchingStep(SECRET, code.substring(0, 3) + " " + code.substring(3), now))
                .isPresent();
        assertThat(Totp.matchingStep(SECRET, code + "0", now)).isEmpty();
        assertThat(Totp.matchingStep(SECRET, code.substring(1), now)).isEmpty();
        assertThat(Totp.matchingStep(SECRET, "abcdef", now)).isEmpty();
        assertThat(Totp.matchingStep(SECRET, "", now)).isEmpty();
    }

    @Test
    void base32MatchesTheRfc4648Vectors() {
        String[][] vectors = {
            {"", ""},
            {"f", "MY"},
            {"fo", "MZXQ"},
            {"foo", "MZXW6"},
            {"foob", "MZXW6YQ"},
            {"fooba", "MZXW6YTB"},
            {"foobar", "MZXW6YTBOI"}
        };
        for (String[] v : vectors) {
            byte[] plain = v[0].getBytes(StandardCharsets.US_ASCII);
            assertThat(Base32.encode(plain)).isEqualTo(v[1]);
            assertThat(Base32.decode(v[1])).isEqualTo(plain);
        }
        assertThat(Base32.decode("mzxw 6ytb oi==")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void aSecretSurvivesBase32() {
        assertThat(Base32.decode(Base32.encode(SECRET))).isEqualTo(SECRET);
    }
}

package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords on the JDK's {@code Mac}: HMAC-SHA1, 6 digits, 30-second
 * steps, and one step of clock drift either way (ADR-0143). What authenticator apps implement by default.
 */
final class Totp {

    static final int DIGITS = 6;
    static final int STEP_SECONDS = 30;

    private static final String HMAC = "HmacSHA1";
    private static final int[] POWERS_OF_TEN = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    private Totp() {}

    static long stepAt(Instant time) {
        return time.getEpochSecond() / STEP_SECONDS;
    }

    /** The code for one step, {@code digits} long (RFC 6238 appendix B lists 8-digit ones). */
    static String code(byte[] secret, long step, int digits) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", e);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format("%0" + digits + "d", binary % POWERS_OF_TEN[digits]);
    }

    /**
     * The step the submitted code is valid for at {@code now}, allowing one step of drift each way;
     * empty when it matches none. Every candidate is compared, and in constant time, so the answer
     * does not reveal how close a guess was.
     */
    static OptionalLong matchingStep(byte[] secret, String submitted, Instant now) {
        String code = submitted.replaceAll("\\s", "");
        if (!code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        byte[] wanted = code.getBytes(StandardCharsets.US_ASCII);
        long current = stepAt(now);
        OptionalLong match = OptionalLong.empty();
        for (long step = current - 1; step <= current + 1; step++) {
            byte[] candidate = code(secret, step, DIGITS).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(candidate, wanted)) {
                match = OptionalLong.of(step);
            }
        }
        return match;
    }
}

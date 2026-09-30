package io.github.sudoitir.artemisstudio.support;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What an authenticator app does with the secret Studio shows (RFC 4226 and 6238, HMAC-SHA1, 6
 * digits, 30-second steps), written separately from Studio's own so the two check each other.
 */
public final class TotpCodes {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpCodes() {}

    /** The code for the current 30-second step. */
    public static String now(String base32Secret) {
        return at(base32Secret, Instant.now());
    }

    public static String at(String base32Secret, Instant time) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(base32Secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(
                    ByteBuffer.allocate(8).putLong(time.getEpochSecond() / 30).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decode(String text) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(Character.toUpperCase(c));
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out.toByteArray();
    }
}

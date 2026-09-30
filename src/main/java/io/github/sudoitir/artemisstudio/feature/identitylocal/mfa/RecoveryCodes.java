package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single-use recovery codes of a user (ADR-0142): 10 codes of 10 Base32 characters, shown as
 * {@code XXXXX-XXXXX} and kept only as the SHA-256 of the code without dashes, upper-cased. Spending
 * one is one atomic UPDATE, so a code used twice, even at once, works once.
 */
@Component
@RequiredArgsConstructor
class RecoveryCodes {

    static final int COUNT = 10;
    static final int LENGTH = 10;

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    /** Replace the user's codes with {@value #COUNT} new ones, returned as shown to the user. The old ones stop working. */
    List<String> issue(UUID userId) {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < COUNT) {
            StringBuilder code = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                code.append(Base32.ALPHABET.charAt(random.nextInt(Base32.ALPHABET.length())));
            }
            codes.add(code.toString());
        }
        jdbc.sql("DELETE FROM local_recovery_code WHERE user_id = :id")
                .param("id", userId)
                .update();
        for (String code : codes) {
            jdbc.sql("INSERT INTO local_recovery_code (user_id, code_hash) VALUES (:id, :hash)")
                    .param("id", userId)
                    .param("hash", hash(code))
                    .update();
        }
        List<String> shown = new ArrayList<>();
        codes.forEach(code -> shown.add(code.substring(0, LENGTH / 2) + "-" + code.substring(LENGTH / 2)));
        return shown;
    }

    /** Whether {@code submitted} was one of the user's unused codes, which it no longer is. */
    boolean spend(UUID userId, String submitted) {
        String code = normalize(submitted);
        if (!code.matches("[" + Base32.ALPHABET + "]{" + LENGTH + "}")) {
            return false;
        }
        return jdbc.sql("""
                                UPDATE local_recovery_code SET used_at = now()
                                WHERE user_id = :id AND code_hash = :hash AND used_at IS NULL
                                """).param("id", userId).param("hash", hash(code)).update() == 1;
    }

    int remaining(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM local_recovery_code WHERE user_id = :id AND used_at IS NULL")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    /** Case, dashes and spaces do not matter to the person typing. */
    static String normalize(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    static byte[] hash(String normalized) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

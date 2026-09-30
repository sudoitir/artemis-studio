package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single-use recovery codes of a user (ADR-0142): 10 codes of 10 Base32 characters, shown as
 * {@code XXXXX-XXXXX} and kept only as the HMAC-SHA256 of the code without dashes, upper-cased, under
 * the installation's {@link RecoveryKey}. A code has 50 bits, so a plain hash would fall to an offline
 * guess from a copy of the table; the keyed one does not. Spending one is one atomic UPDATE, so a
 * code used twice, even at once, works once.
 */
@Component
class RecoveryCodes {

    static final int COUNT = 10;
    static final int LENGTH = 10;

    private static final String HMAC = "HmacSHA256";

    private final JdbcClient jdbc;
    private final RecoveryKey key;
    private final SecureRandom random = new SecureRandom();

    RecoveryCodes(JdbcClient jdbc, RecoveryKey key) {
        this.jdbc = jdbc;
        this.key = key;
    }

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

    void removeAll(UUID userId) {
        jdbc.sql("DELETE FROM local_recovery_code WHERE user_id = :id")
                .param("id", userId)
                .update();
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

    byte[] hash(String normalized) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key.get());
            return mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}

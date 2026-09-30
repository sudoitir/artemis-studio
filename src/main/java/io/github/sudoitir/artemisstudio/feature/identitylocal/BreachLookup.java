package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The optional online check of a new password against Have I Been Pwned's range API (ADR-0144), switched by
 * the runtime setting {@code identity-local.password.breach-lookup}.
 * Only the first five characters of the password's SHA-1 leave Studio; the service returns every
 * suffix under that prefix and the match is made here. It fails open: an outage or a timeout logs
 * a warning and lets the password through, so a network problem never blocks a password change.
 */
@Component
@Slf4j
public class BreachLookup {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final SettingsService settings;
    private final RestClient restClient;

    @Autowired
    BreachLookup(SettingsService settings) {
        this(
                settings,
                RestClient.builder()
                        .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                                .build(HttpClientSettings.defaults()
                                        .withConnectTimeout(TIMEOUT)
                                        .withReadTimeout(TIMEOUT)))
                        .build());
    }

    BreachLookup(SettingsService settings, RestClient restClient) {
        this.settings = settings;
        this.restClient = restClient;
    }

    /** Whether the password appears in a known breach; false when the lookup is off or unreachable. */
    boolean isBreached(String password) {
        if (!settings.bool(IdentityLocalSettings.BREACH_LOOKUP)) {
            return false;
        }
        String hash = sha1(password);
        String prefix = hash.substring(0, 5);
        String suffix = hash.substring(5);
        try {
            String body = restClient
                    .get()
                    .uri("https://api.pwnedpasswords.com/range/{prefix}", prefix)
                    .header("Add-Padding", "true")
                    .retrieve()
                    .body(String.class);
            return body != null
                    && body.lines().anyMatch(line -> {
                        String[] parts = line.trim().split(":");
                        // Padding entries carry a count of 0 and are not real matches.
                        return parts.length == 2 && parts[0].equals(suffix) && !parts[1].equals("0");
                    });
        } catch (RuntimeException e) {
            log.warn("Breached-password lookup failed; allowing the password: {}", e.toString());
            return false;
        }
    }

    // The range API is keyed by SHA-1; it is a lookup key here, not a password hash.
    private static String sha1(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

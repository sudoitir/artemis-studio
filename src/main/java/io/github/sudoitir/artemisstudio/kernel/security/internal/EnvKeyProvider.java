package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import io.github.sudoitir.artemisstudio.kernel.security.Keyring;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import org.springframework.core.env.Environment;

/**
 * Keys from {@code ARTEMIS_STUDIO_SECRET_KEY} ({@code artemis-studio.secret-key}): a bare base64 key is version 1,
 * or {@code 1=<b64>,2=<b64>} names each version.
 */
class EnvKeyProvider implements KeyProvider {

    private static final Pattern VERSIONED = Pattern.compile("(\\d+)=(.+)");

    private final Environment environment;

    EnvKeyProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Keyring load() {
        String configured = environment.getProperty("artemis-studio.secret-key");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Secret key provider 'env': ARTEMIS_STUDIO_SECRET_KEY "
                    + "(artemis-studio.secret-key) is not set. Provide base64 of 32 random bytes, "
                    + "e.g. `openssl rand -base64 32`.");
        }
        TreeMap<Integer, SecretKey> keys = new TreeMap<>();
        for (String part : configured.trim().split(",")) {
            Matcher versioned = VERSIONED.matcher(part.trim());
            int version = versioned.matches() ? Integer.parseInt(versioned.group(1)) : 1;
            String base64 = versioned.matches() ? versioned.group(2) : part;
            if (version < 1 || keys.containsKey(version)) {
                throw new IllegalStateException("Secret key provider 'env': key version " + version
                        + " is repeated or below 1 in ARTEMIS_STUDIO_SECRET_KEY.");
            }
            keys.put(version, Keyring.parse(name(), "key version " + version, base64));
        }
        return new Keyring(keys);
    }

    @Override
    public Optional<String> secret(String name) {
        String value =
                environment.getProperty("ARTEMIS_STUDIO_" + name.toUpperCase().replace('-', '_'));
        return Optional.ofNullable(value).filter(v -> !v.isBlank());
    }

    @Override
    public String name() {
        return "env";
    }
}

package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Seals the originals of masked values beside a stored row (ADR-0075 D4). The AAD binds a blob to the row it
 * was sealed for, so a blob copied onto another row does not open. Credentials never reach here: the policy
 * does not collect them as sealable.
 */
@Component
@RequiredArgsConstructor
public class ContentSealer {

    /** Ciphertext with its GCM tag, and the nonce it was produced with. */
    public record SealedOriginals(byte[] ciphertext, byte[] nonce) {}

    private static final TypeReference<Map<String, String>> ORIGINALS = new TypeReference<>() {};

    private final SecretVault vault;
    private final ObjectMapper mapper;

    /** Null when there is nothing to seal. */
    public SealedOriginals seal(String aad, Map<String, String> originals) {
        if (originals == null || originals.isEmpty()) {
            return null;
        }
        SecretVault.Sealed sealed = vault.encrypt(aad, mapper.writeValueAsString(originals));
        return new SealedOriginals(sealed.ciphertext(), sealed.nonce());
    }

    /**
     * The originals, or empty when none were sealed or the blob does not open under this AAD. Never throws: a row
     * whose originals cannot be recovered stays masked rather than failing the read.
     */
    public Map<String, String> unseal(String aad, byte[] ciphertext, byte[] nonce) {
        if (ciphertext == null || nonce == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(vault.decrypt(aad, ciphertext, nonce), ORIGINALS);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Stored masked content with sealed originals laid back over it, keyed as {@link GovernedMessage#key}. */
    public static MessageContent restore(MessageContent stored, Map<String, String> originals) {
        if (originals.isEmpty()) {
            return stored;
        }
        Map<String, String> headers = new HashMap<>(stored.headers());
        Map<String, Object> properties = new LinkedHashMap<>(stored.properties());
        String body = stored.body();
        for (Map.Entry<String, String> original : originals.entrySet()) {
            String key = original.getKey();
            int colon = key.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String path = key.substring(colon + 1);
            switch (key.substring(0, colon)) {
                case "HEADER" -> headers.put(path, original.getValue());
                case "PROPERTY" -> properties.put(path, original.getValue());
                case "BODY" -> body = original.getValue();
                default -> {
                    // An unknown location is ignored rather than guessed at.
                }
            }
        }
        return new MessageContent(headers, properties, body, stored.base64(), stored.contentType());
    }
}

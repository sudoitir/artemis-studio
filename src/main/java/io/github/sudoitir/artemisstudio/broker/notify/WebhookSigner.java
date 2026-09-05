package io.github.sudoitir.artemisstudio.broker.notify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standard Webhooks HMAC signing (ADR-0036): {@code webhook-signature: v1,<base64
 * HMAC-SHA256>} over {@code "{id}.{timestamp}.{body}"}. The secret follows the
 * spec's serialization — base64, optionally prefixed {@code whsec_} — so a
 * channel's stored secret can be pasted straight from another Standard Webhooks
 * sender.
 */
public final class WebhookSigner {

    private static final String PREFIX = "whsec_";

    private WebhookSigner() {}

    public static String sign(String id, long timestampSeconds, String body, String secret) {
        byte[] key = decodeSecret(secret);
        String signedContent = id + "." + timestampSeconds + "." + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signature = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign webhook payload", e);
        }
    }

    private static byte[] decodeSecret(String secret) {
        String base64 = secret.startsWith(PREFIX) ? secret.substring(PREFIX.length()) : secret;
        return Base64.getDecoder().decode(base64);
    }
}

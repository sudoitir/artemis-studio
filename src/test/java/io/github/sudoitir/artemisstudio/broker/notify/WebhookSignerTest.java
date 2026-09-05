package io.github.sudoitir.artemisstudio.broker.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WebhookSigner} against the Standard Webhooks spec's documented
 * construction: {@code "{id}.{timestamp}.{body}"} HMAC-SHA256'd with the
 * base64-decoded secret (optionally {@code whsec_}-prefixed), rendered as
 * {@code v1,<base64 signature>}.
 */
class WebhookSignerTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String ID = "msg_2KWPBgLlAfxdpx2AI54pPJ85f4W";
    private static final long TIMESTAMP = 1674087231L;
    private static final String BODY = "{\"type\":\"contact.created\"}";

    @Test
    void signatureMatchesAnIndependentlyComputedHmac() throws Exception {
        String signature = WebhookSigner.sign(ID, TIMESTAMP, BODY, SECRET);

        assertThat(signature).startsWith("v1,");
        String expected = "v1," + independentHmac(SECRET, ID + "." + TIMESTAMP + "." + BODY);
        assertThat(signature).isEqualTo(expected);
    }

    @Test
    void signatureAcceptsAnUnprefixedBase64Secret() throws Exception {
        String bareSecret = SECRET.substring("whsec_".length());
        String signature = WebhookSigner.sign(ID, TIMESTAMP, BODY, bareSecret);

        assertThat(signature).isEqualTo(WebhookSigner.sign(ID, TIMESTAMP, BODY, SECRET));
    }

    @Test
    void differentTimestampsProduceDifferentSignatures() {
        String a = WebhookSigner.sign(ID, TIMESTAMP, BODY, SECRET);
        String b = WebhookSigner.sign(ID, TIMESTAMP + 1, BODY, SECRET);
        assertThat(a).isNotEqualTo(b);
    }

    private static String independentHmac(String base64Secret, String signedContent) throws Exception {
        String bare = base64Secret.startsWith("whsec_") ? base64Secret.substring(6) : base64Secret;
        byte[] key = Base64.getDecoder().decode(bare);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] sig = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig);
    }
}

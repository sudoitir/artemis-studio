package io.github.sudoitir.artemisstudio.feature.alerting;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Base64;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-kind validation of a channel before it is stored or tested (ADR-0105 D6). A rejection is an
 * {@link IllegalArgumentException} whose message names the field at fault, so the form can put it
 * beside that field rather than in a toast.
 */
@Component
public class ChannelConfigValidator {

    public static final Set<String> KINDS = Set.of("WEBHOOK", "SLACK", "EMAIL", "TEAMS", "PAGERDUTY");

    private static final Set<String> SECURITY =
            Set.of(EmailChannelConfig.STARTTLS, EmailChannelConfig.TLS, EmailChannelConfig.NONE);

    private final ObjectMapper mapper;

    public ChannelConfigValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param secret the secret as submitted; blank means "keep the stored one"
     * @param secretStored whether the channel already holds a secret to fall back on
     */
    public void validate(String kind, String configJson, String secret, boolean secretStored) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("unknown channel kind: " + kind);
        }
        JsonNode config;
        try {
            config = mapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("config: not valid JSON");
        }
        if (!config.isObject()) {
            throw new IllegalArgumentException("config: must be a JSON object");
        }
        boolean hasSecret = secret != null && !secret.isBlank();
        switch (kind) {
            case "WEBHOOK" -> {
                requireUrl("url", text(config, "url"));
                requireSecret("secret", hasSecret, secretStored, "a signing secret");
                if (hasSecret) {
                    requireBase64Secret(secret.trim());
                }
            }
            case "SLACK" -> {
                requireSecret("secret", hasSecret, secretStored, "the Slack webhook URL");
                if (hasSecret) {
                    requireUrl("secret", secret.trim());
                }
            }
            case "TEAMS" -> {
                requireSecret("secret", hasSecret, secretStored, "the Teams webhook URL");
                if (hasSecret) {
                    requireUrl("secret", secret.trim());
                }
            }
            case "PAGERDUTY" -> {
                String url = text(config, "url");
                if (url != null) {
                    requireUrl("url", url);
                }
                requireSecret("secret", hasSecret, secretStored, "the routing key");
                if (hasSecret
                        && (url == null || url.equals(PagerDutySender.DEFAULT_URL))
                        && secret.trim().length() != 32) {
                    throw new IllegalArgumentException(
                            "secret: a PagerDuty routing key is 32 characters (an Events API v2 integration key)");
                }
            }
            case "EMAIL" -> validateEmail(EmailChannelConfig.parse(configJson, mapper));
            default -> throw new IllegalArgumentException("unknown channel kind: " + kind);
        }
    }

    private static void validateEmail(EmailChannelConfig c) {
        if (c.host() == null) {
            throw new IllegalArgumentException("host: the SMTP server is required");
        }
        if (c.port() < 1 || c.port() > 65535) {
            throw new IllegalArgumentException("port: must be between 1 and 65535");
        }
        if (!SECURITY.contains(c.security())) {
            throw new IllegalArgumentException("security: must be STARTTLS, TLS or NONE");
        }
        if (c.from() == null) {
            throw new IllegalArgumentException("from: the sender address is required");
        }
        requireAddress("from", c.from());
        if (c.to().isEmpty()) {
            throw new IllegalArgumentException("to: at least one recipient is required");
        }
        for (String address : c.to()) {
            requireAddress("to", address);
        }
        if (c.subjectPrefix() != null && c.subjectPrefix().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("subjectPrefix: must be a single line");
        }
    }

    private static void requireAddress(String field, String address) {
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
        } catch (AddressException e) {
            throw new IllegalArgumentException(field + ": \"" + address + "\" is not a valid email address");
        }
    }

    private static void requireUrl(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + ": a URL is required");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException(field + ": must be an http or https URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(field + ": the URL must name a host");
        }
    }

    private static void requireSecret(String field, boolean hasSecret, boolean secretStored, String what) {
        if (!hasSecret && !secretStored) {
            throw new IllegalArgumentException(field + ": " + what + " is required");
        }
    }

    /** {@link WebhookSigner} decodes the secret as base64, optionally {@code whsec_}-prefixed. */
    private static void requireBase64Secret(String secret) {
        String base64 = secret.startsWith("whsec_") ? secret.substring("whsec_".length()) : secret;
        try {
            if (Base64.getDecoder().decode(base64).length < 16) {
                throw new IllegalArgumentException("secret: the signing secret must decode to at least 16 bytes");
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("secret:")) {
                throw e;
            }
            throw new IllegalArgumentException("secret: the signing secret must be base64, optionally prefixed whsec_");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asString().isBlank()
                ? null
                : v.asString().trim();
    }
}

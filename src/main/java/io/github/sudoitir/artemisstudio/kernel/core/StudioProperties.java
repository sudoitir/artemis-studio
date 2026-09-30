package io.github.sudoitir.artemisstudio.kernel.core;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Studio-wide deploy settings.
 *
 * @param publicUrl the address people open Studio at, e.g. {@code https://studio.example.com}
 *     ({@code ARTEMIS_STUDIO_PUBLIC_URL}). Alert notifications link back to it, and it is the
 *     relying party of passkeys (ADR-0142), which are tied to its host: changing the host strands
 *     the passkeys enrolled under the old one. Blank means it is not set. A trailing slash is dropped.
 */
@ConfigurationProperties(prefix = "artemis-studio")
public record StudioProperties(@DefaultValue("") String publicUrl) {

    public StudioProperties {
        publicUrl = publicUrl == null ? "" : publicUrl.strip();
        if (publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }
        if (!publicUrl.isEmpty() && !isAddress(publicUrl)) {
            throw new IllegalArgumentException("artemis-studio.public-url (ARTEMIS_STUDIO_PUBLIC_URL) must be an http"
                    + " or https address with a host, like https://studio.example.com, not '" + publicUrl + "'");
        }
    }

    public boolean hasPublicUrl() {
        return !publicUrl.isEmpty();
    }

    private static boolean isAddress(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

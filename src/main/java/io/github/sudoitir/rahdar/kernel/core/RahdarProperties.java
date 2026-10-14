package io.github.sudoitir.rahdar.kernel.core;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rahdar-wide deploy settings.
 *
 * @param publicUrl the address people open Rahdar at, e.g. {@code https://rahdar.example.com}
 *     ({@code RAHDAR_PUBLIC_URL}). Alert notifications link back to it, and it is the
 *     relying party of passkeys (ADR-0143), which are tied to its host: changing the host strands
 *     the passkeys enrolled under the old one. Blank means it is not set. A trailing slash is dropped.
 */
@ConfigurationProperties(prefix = "rahdar")
public record RahdarProperties(@DefaultValue("") String publicUrl) {

    public RahdarProperties {
        publicUrl = publicUrl == null ? "" : publicUrl.strip();
        if (publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }
        if (!publicUrl.isEmpty() && !isAddress(publicUrl)) {
            throw new IllegalArgumentException("rahdar.public-url (RAHDAR_PUBLIC_URL) must be an http"
                    + " or https address with a host, like https://rahdar.example.com, not '" + publicUrl + "'");
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
        } catch (IllegalArgumentException _) {
            return false;
        }
    }
}

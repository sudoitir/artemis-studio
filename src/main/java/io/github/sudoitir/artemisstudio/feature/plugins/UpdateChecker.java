package io.github.sudoitir.artemisstudio.feature.plugins;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.SemVer;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginSummary;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Check for updates" (design.md §6): only when an installer asks — Studio makes no request on
 * its own — each plugin's {@code updateUrl} answers {@code {version, url, sha256, changeNotes}}.
 * Both requests are https, follow no redirect, and are bounded in time and size; the jar must
 * hash to the {@code sha256} the answer named, and is then inspected like any upload.
 */
@Component
public class UpdateChecker {

    static final int MAX_OFFER_BYTES = 64 * 1024;
    static final long MAX_JAR_BYTES = 50L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(2);

    /** What one plugin's update URL offers; {@code error} says why it could not be asked. */
    public record Available(
            String id, String currentVersion, String availableVersion, String changeNotes, String error) {}

    record Offer(String version, String url, String sha256, String changeNotes) {}

    private final JsonMapper json;
    private final HttpClient client;

    UpdateChecker(JsonMapper json) {
        this.json = json;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
    }

    List<Available> check(List<PluginSummary> installed) {
        return installed.stream()
                .filter(p -> p.status() != PluginInstallStatus.UNINSTALLED
                        && p.descriptor() != null
                        && p.descriptor().updateUrl() != null)
                .map(this::check)
                .toList();
    }

    private Available check(PluginSummary plugin) {
        try {
            Offer offer = offer(plugin.descriptor().updateUrl());
            boolean newer = SemVer.compare(offer.version(), plugin.version()) > 0;
            return new Available(
                    plugin.id(),
                    plugin.version(),
                    newer ? offer.version() : null,
                    newer ? offer.changeNotes() : null,
                    null);
        } catch (Exception e) {
            return new Available(plugin.id(), plugin.version(), null, null, e.getMessage());
        }
    }

    /** Downloads the newer version {@code plugin}'s update URL offers, to a private temp file. */
    Path download(PluginSummary plugin) {
        if (plugin.descriptor() == null || plugin.descriptor().updateUrl() == null) {
            throw refused("no-update-url", plugin.id() + " does not name an update URL.", "Upload the new version.");
        }
        try {
            Offer offer = offer(plugin.descriptor().updateUrl());
            if (SemVer.compare(offer.version(), plugin.version()) <= 0) {
                throw refused("no-update", "No version newer than " + plugin.version() + " is offered.", "");
            }
            if (offer.sha256() == null || !offer.sha256().matches("(?i)[0-9a-f]{64}")) {
                throw refused("update-invalid", "The update does not state a sha256 for its jar.", "Ask the vendor.");
            }
            HttpResponse<InputStream> response = get(offer.url(), DOWNLOAD_TIMEOUT);
            Path jar = Files.createTempFile(
                    "plugin-update-",
                    ".jar",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = response.body();
                    OutputStream out = new DigestOutputStream(Files.newOutputStream(jar), digest)) {
                if (copyBounded(in, out, MAX_JAR_BYTES) > MAX_JAR_BYTES) {
                    Files.deleteIfExists(jar);
                    throw refused("update-too-large", "The update is larger than 50 MB.", "Ask the vendor.");
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(offer.sha256())) {
                Files.deleteIfExists(jar);
                throw refused(
                        "update-checksum-mismatch",
                        "The downloaded jar hashes to %s, not the %s its update URL named."
                                .formatted(actual, offer.sha256()),
                        "Do not install it; tell the vendor.");
            }
            return jar;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw refused(
                    "update-unavailable", "The update could not be downloaded: " + e.getMessage(), "Try again later.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw refused("update-unavailable", "The download was interrupted.", "Try again.");
        }
    }

    private Offer offer(String updateUrl) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = get(updateUrl, TIMEOUT);
        byte[] body;
        try (InputStream in = response.body()) {
            body = in.readNBytes(MAX_OFFER_BYTES + 1);
        }
        if (body.length > MAX_OFFER_BYTES) {
            throw new IOException("the update description is larger than 64 KB");
        }
        Offer offer = json.readValue(body, Offer.class);
        if (offer.version() == null || offer.url() == null) {
            throw new IOException("the update description has no version or url");
        }
        return offer;
    }

    /** An https GET that must answer 200 itself: a redirect is an answer, not something to follow. */
    private HttpResponse<InputStream> get(String url, Duration timeout) throws IOException, InterruptedException {
        URI uri = requireHttps(url);
        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(uri).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("%s answered %d".formatted(uri.getHost(), response.statusCode()));
        }
        return response;
    }

    static URI requireHttps(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("not a URL: " + url);
        }
        if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IOException("only https URLs are fetched: " + url);
        }
        return uri;
    }

    /** Copies at most {@code limit + 1} bytes, so a caller can tell "too large" without reading it all. */
    private static long copyBounded(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while (total <= limit && (n = in.read(buffer, 0, (int) Math.min(buffer.length, limit + 1 - total))) > 0) {
            out.write(buffer, 0, n);
            total += n;
        }
        return total;
    }

    private static PluginRefusedException refused(String code, String message, String fix) {
        return new PluginRefusedException(List.of(new Violation(code, message, fix)));
    }
}

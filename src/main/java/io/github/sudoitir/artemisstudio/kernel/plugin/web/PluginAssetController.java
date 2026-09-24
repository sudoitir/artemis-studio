package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Active;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /plugin-ui/<id>/<sha8>/**} (design.md §7, task 6.10): a plugin's Module Federation
 * remote and its icon, read by <em>exact</em> {@link JarFile} entry out of the currently
 * {@link Active} runtime's own materialized jar — never off disk by path, and never through any
 * resolution that could walk out of {@code META-INF/artemis-studio/}. {@code sha8} must equal the
 * first 8 hex characters of that runtime's own {@link PluginRuntime#sha256()} exactly (task 6.9's
 * manifest advertises the same prefix), so a stale or guessed URL 404s instead of ever serving a
 * different version's bytes under a URL that looks pinned to this one.
 *
 * <p>{@code icon.svg} is the one path served from {@code META-INF/artemis-studio/icon.svg} instead
 * of the {@code ui/} tree, and carries {@code Content-Security-Policy: sandbox} so a plugin's icon
 * — an arbitrary SVG a plugin author supplied — can only ever be rendered inert, through
 * {@code <img>}, never executed. Everything else is under {@code ui/}, with an exact
 * {@code Content-Type} by extension, {@code X-Content-Type-Options: nosniff} and a
 * {@code private, immutable} cache lifetime — the URL is content-addressed by {@code sha8}, so a
 * cached response can never go stale under the same URL.
 */
@RestController
@Hidden
@RequiredArgsConstructor
@Slf4j
public class PluginAssetController {

    private static final String ICON_ENTRY = "META-INF/artemis-studio/icon.svg";
    private static final String UI_PREFIX = "META-INF/artemis-studio/ui/";
    private static final int SHA_PREFIX_LENGTH = 8;

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("css", "text/css"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"));

    private final PluginRuntimeRegistry registry;
    private final JsonMapper json;

    @GetMapping("/plugin-ui/{id}/{sha8}/{*path}")
    public void asset(
            @PathVariable String id, @PathVariable String sha8, @PathVariable String path, HttpServletResponse response)
            throws IOException {
        String assetPath = normalize(path);
        Optional<PluginRuntime> runtime = activeRuntime(id);
        if (runtime.isEmpty() || assetPath.isEmpty() || !matchesSha8(runtime.get(), sha8) || !isPathSafe(assetPath)) {
            notFound(response, id);
            return;
        }

        boolean icon = assetPath.equals("icon.svg");
        String entryName = icon ? ICON_ENTRY : UI_PREFIX + assetPath;
        serve(runtime.get(), entryName, icon, response, id);
    }

    private Optional<PluginRuntime> activeRuntime(String id) {
        return registry.get(id).filter(Active.class::isInstance).map(slot -> ((Active) slot).runtime());
    }

    private boolean matchesSha8(PluginRuntime runtime, String sha8) {
        return runtime.sha256().regionMatches(0, sha8, 0, SHA_PREFIX_LENGTH) && sha8.length() == SHA_PREFIX_LENGTH;
    }

    /** {@code {*path}} includes its own leading {@code /}; this strips it so the rest of this
     * class works with a plain, entry-relative path. */
    private static String normalize(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /** Traversal-safe by construction (an exact {@link JarFile} entry-name lookup cannot walk out
     * of the jar), but every one of these is rejected explicitly anyway, per task 6.10, rather than
     * relying only on that lookup never matching a hostile-looking name. */
    private static boolean isPathSafe(String assetPath) {
        return !assetPath.contains("..")
                && !assetPath.contains("\\")
                && !assetPath.contains("%")
                && !assetPath.startsWith("/");
    }

    private void serve(PluginRuntime runtime, String entryName, boolean icon, HttpServletResponse response, String id)
            throws IOException {
        try (JarFile jar = new JarFile(runtime.jarPath().toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                notFound(response, id);
                return;
            }
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(contentTypeOf(entryName));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable");
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (icon) {
                response.setHeader("Content-Security-Policy", "sandbox");
            }
            try (var in = jar.getInputStream(entry)) {
                in.transferTo(response.getOutputStream());
            }
        } catch (IOException e) {
            log.warn("Plugin '{}' UI asset '{}' could not be read from its jar", id, entryName, e);
            notFound(response, id);
        }
    }

    private static String contentTypeOf(String entryName) {
        int dot = entryName.lastIndexOf('.');
        String extension = dot < 0 ? "" : entryName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private void notFound(HttpServletResponse response, String id) throws IOException {
        ProblemDetail problem = Problems.of(
                HttpStatus.NOT_FOUND, "plugin-asset-not-found", "Not Found", "No such asset for plugin '" + id + "'.");
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}

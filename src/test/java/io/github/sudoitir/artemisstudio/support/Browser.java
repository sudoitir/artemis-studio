package io.github.sudoitir.artemisstudio.support;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Base64;

/**
 * A browser for tests that need the real session store: a cookie jar over real HTTP, because
 * MockMvc's mock sessions never reach the JDBC store the session endpoints read. It sends the CSRF
 * token the way the console does, and claims a client address through {@code X-Forwarded-For}
 * (loopback is a trusted proxy in tests), so a test can give each browser, or each attempt, its own
 * source for the sign-in throttle.
 */
public final class Browser {

    private final CookieManager cookies = new CookieManager();
    private final HttpClient http =
            HttpClient.newBuilder().cookieHandler(cookies).build();
    private final String base;
    private final String userAgent;
    private String source;

    public Browser(int port, String userAgent, String source) {
        this.base = "http://localhost:" + port;
        this.userAgent = userAgent;
        this.source = source;
    }

    /** From now on, requests claim to come from this address. */
    public Browser from(String address) {
        this.source = address;
        return this;
    }

    public HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        return request(method, path, body, true, headers);
    }

    /** A request that leaves out the CSRF token. */
    public HttpResponse<String> sendWithoutCsrf(String method, String path, String body) throws Exception {
        return request(method, path, body, false);
    }

    public HttpResponse<String> post(String path, String json) throws Exception {
        return send("POST", path, json);
    }

    public int status(String method, String path) throws Exception {
        return send(method, path, null).statusCode();
    }

    /** The id of the session this browser holds, as the store knows it; null before it has one. */
    public String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("SESSION"))
                .map(HttpCookie::getValue)
                .findFirst()
                .map(v -> new String(Base64.getDecoder().decode(v)))
                .orElse(null);
    }

    private HttpResponse<String> request(String method, String path, String body, boolean csrf, String... headers)
            throws Exception {
        if (csrf && xsrf() == null && !method.equals("GET")) {
            request("GET", "/api/v1/auth/providers", null, false);
        }
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                .header("User-Agent", userAgent);
        if (csrf && xsrf() != null) {
            request.header("X-XSRF-TOKEN", xsrf());
        }
        if (source != null) {
            request.header("X-Forwarded-For", source);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), BodyHandlers.ofString());
    }

    private String xsrf() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }
}

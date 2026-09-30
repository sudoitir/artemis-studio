package io.github.sudoitir.artemisstudio.kernel.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * The cookie that marks a browser as a trusted device (ADR-0143): 32 random bytes, of which only the
 * hash is kept. HttpOnly, {@code SameSite=Strict} and limited to the sign-in endpoints, so the browser
 * sends it nowhere else. Like the session cookie it is {@code Secure} whenever the request came over
 * HTTPS as Tomcat resolves it (through the trusted proxies), so plain-HTTP local development works.
 */
public final class TrustedDeviceCookie {

    public static final String NAME = "as_trusted_device";

    private static final String PATH = "/api/v1/auth";

    private TrustedDeviceCookie() {}

    /** The token the browser presented, if any. */
    public static Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** Ask the browser to keep {@code token} for {@code lifetime}. */
    public static void set(HttpServletRequest request, HttpServletResponse response, String token, Duration lifetime) {
        response.addHeader(
                HttpHeaders.SET_COOKIE, cookie(request, token, lifetime).toString());
    }

    /** Ask the browser to forget the cookie. */
    public static void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(
                HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString());
    }

    private static ResponseCookie cookie(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}

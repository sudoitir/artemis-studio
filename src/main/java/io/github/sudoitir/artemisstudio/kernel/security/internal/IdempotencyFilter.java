package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code Idempotency-Key} on every mutating {@code /api/v1} request (ADR-0148). A request with the header
 * is claimed for the calling user; a repeat with the same request replays the first result, and any other
 * repeat is refused. Registered at the default order, so it runs after the security chain (CSRF and
 * authentication have already decided) and sees the principal. Requests without the header, unauthenticated
 * ones and the plugin gateway, whose plugins own the header, pass untouched.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
class IdempotencyFilter extends OncePerRequestFilter {

    static final String KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern KEY = Pattern.compile("[\\x20-\\x7E]{1,255}");
    private static final AntPathMatcher PATHS = new AntPathMatcher();
    /** Bulk bodies are capped well below this (ADR-0022); a larger one is not worth buffering. */
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private final IdempotencyRecords records;
    private final JsonMapper json;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !MUTATING.contains(request.getMethod())
                || request.getHeader(KEY_HEADER) == null
                || !path.startsWith("/api/v1/")
                || PATHS.match("/api/v1/p/**", path)
                || PATHS.match("/api/v1/clusters/*/p/**", path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof StudioPrincipal principal) || principal.userId() == null) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(KEY_HEADER);
        if (!KEY.matcher(key).matches()) {
            refuse(
                    response,
                    HttpStatus.BAD_REQUEST,
                    "invalid-idempotency-key",
                    "Invalid Idempotency-Key",
                    "The key must be 1 to 255 printable ASCII characters.");
            return;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.regionMatches(true, 0, "multipart/", 0, 10)) {
            refuse(
                    response,
                    HttpStatus.BAD_REQUEST,
                    "idempotency-unsupported",
                    "Idempotency-Key not supported",
                    "A multipart upload cannot be made idempotent; send it without the key.");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            refuse(
                    response,
                    HttpStatus.BAD_REQUEST,
                    "idempotency-unsupported",
                    "Idempotency-Key not supported",
                    "The request body is too large to record for an Idempotency-Key.");
            return;
        }
        UUID user = principal.userId();
        String fingerprint = fingerprint(request, body);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (records.claim(user, key, fingerprint)) {
                run(new Replayable(request, body), response, chain, user, key);
                return;
            }
            Optional<IdempotencyRecords.Stored> existing = records.find(user, key);
            if (existing.isEmpty()) {
                continue; // released between the claim and the read: claim again
            }
            answerRepeat(existing.get(), fingerprint, response);
            return;
        }
        refuse(
                response,
                HttpStatus.CONFLICT,
                "idempotency-in-progress",
                "Request in progress",
                "A request with this Idempotency-Key is still running.");
    }

    private void run(HttpServletRequest request, HttpServletResponse response, FilterChain chain, UUID user, String key)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean recorded = false;
        try {
            chain.doFilter(request, wrapped);
            int status = wrapped.getStatus();
            // A server error, and a refusal that says nothing about the request (authorization, the rate
            // limit), are not results: the retry runs.
            if (status < 500 && status != 401 && status != 403 && status != 429) {
                records.complete(user, key, status, wrapped.getContentType(), wrapped.getContentAsByteArray());
                recorded = true;
            }
        } finally {
            if (!recorded) {
                records.release(user, key);
            }
        }
        wrapped.copyBodyToResponse();
    }

    private void answerRepeat(IdempotencyRecords.Stored stored, String fingerprint, HttpServletResponse response)
            throws IOException {
        if (!stored.fingerprint().equals(fingerprint)) {
            refuse(
                    response,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "idempotency-key-reused",
                    "Idempotency-Key reused",
                    "This key was used for a different request. Send a new key for a new request.");
        } else if (!stored.done()) {
            response.setHeader("Retry-After", "1");
            refuse(
                    response,
                    HttpStatus.CONFLICT,
                    "idempotency-in-progress",
                    "Request in progress",
                    "A request with this Idempotency-Key is still running.");
        } else {
            response.setStatus(stored.status());
            response.setHeader(REPLAYED_HEADER, "true");
            if (stored.contentType() != null) {
                response.setContentType(stored.contentType());
            }
            response.getOutputStream().write(stored.body() == null ? new byte[0] : stored.body());
        }
    }

    private void refuse(HttpServletResponse response, HttpStatus status, String slug, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Problems.of(status, slug, title, detail));
    }

    /** SHA-256 over method, path, sorted query and body, so a dry run and the real run differ. */
    private static String fingerprint(HttpServletRequest request, byte[] body) {
        String query = request.getQueryString();
        String[] pairs = query == null ? new String[0] : query.split("&");
        Arrays.sort(pairs);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {request.getMethod(), request.getRequestURI(), String.join("&", pairs)}) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The request with its body read ahead of time, so the fingerprint and the handler both see it. */
    private static final class Replayable extends HttpServletRequestWrapper {

        private final byte[] body;

        Replayable(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("blocking reads only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}

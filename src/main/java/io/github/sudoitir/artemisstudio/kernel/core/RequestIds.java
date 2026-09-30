package io.github.sudoitir.artemisstudio.kernel.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The id a request is known by in logs, audit rows and error bodies: the caller's {@code X-Request-Id} when it is a
 * plain token, otherwise a fresh UUID. A caller-chosen value with line breaks or markup would otherwise forge log
 * lines and travel into every place the id is shown.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestIds() {}

    public static String of(HttpServletRequest request) {
        String header = request == null ? null : request.getHeader(HEADER);
        return header != null && SAFE.matcher(header).matches()
                ? header
                : UUID.randomUUID().toString();
    }
}

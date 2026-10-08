package io.github.sudoitir.artemisstudio.kernel.approval.web;

import io.github.sudoitir.artemisstudio.kernel.gate.GateContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the requester's reason, {@value GateContext#REASON_HEADER} (URL-encoded UTF-8, since a header carries only
 * ASCII safely), to {@link GateContext#REASON} for the request, where the approval gate reads it. The gate checks its
 * length; a value that is not valid URL encoding is taken as it is.
 */
@Component
public class ApprovalReasonFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(GateContext.REASON_HEADER);
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        String reason;
        try {
            reason = URLDecoder.decode(header, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            reason = header;
        }
        try {
            ScopedValue.where(GateContext.REASON, reason).call(() -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}

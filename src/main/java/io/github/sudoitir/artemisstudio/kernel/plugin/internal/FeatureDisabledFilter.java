package io.github.sudoitir.artemisstudio.kernel.plugin.internal;

import io.github.sudoitir.artemisstudio.kernel.plugin.api.Contract;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.api.FeatureRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a request for a disabled feature's API with a {@code 404} that says so
 * and names the property that enables it (feature-modules spec). Registered at the
 * default order, so it runs after the security filter chain: an unauthenticated
 * caller still gets {@code 401} and learns nothing about the installation.
 */
@Component
@RequiredArgsConstructor
class FeatureDisabledFilter extends OncePerRequestFilter {

    static final URI TYPE = URI.create("https://artemis-studio.dev/problems/feature-disabled");

    private final FeatureRegistry registry;
    private final JsonMapper json;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<FeatureDescriptor> owner = registry.disabledOwnerOf(request.getRequestURI());
        if (owner.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        FeatureDescriptor feature = owner.get();
        String property = Contract.enabledProperty(feature.id());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                feature.title() + " is disabled on this installation. Set " + property + "=true to enable it.");
        problem.setType(TYPE);
        problem.setTitle("Feature disabled");
        problem.setProperty("featureId", feature.id());
        problem.setProperty("property", property);
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}

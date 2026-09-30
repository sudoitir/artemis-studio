package io.github.sudoitir.artemisstudio.app;

import io.github.sudoitir.artemisstudio.support.ApiContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Contract tests (api-contract spec, ADR-0148): every JSON response a
 * MockMvc test gets from an {@code /api/v1} handler is checked against {@code web/openapi.json} by
 * {@link ApiContract}, and the test that made the request fails ({@code ApiContractExtension}). It is an
 * interceptor of the application context, not a MockMvc customizer, in the scanned {@code app} package, so it reaches the tests that build their
 * own MockMvc from the context as well. The plugin gateway belongs to each plugin and is skipped.
 */
@Configuration
@ConditionalOnProperty(name = "api.contract", havingValue = "true", matchIfMissing = true)
public class ApiContractConfig implements WebMvcConfigurer {

    /** What the current test's requests got wrong; the extension reports and clears it. */
    public static final ThreadLocal<List<String>> VIOLATIONS = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(
                    HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String type = response.getContentType();
                if (pattern == null
                        || !pattern.startsWith("/api/")
                        || pattern.contains("/p/")
                        || type == null
                        || !type.contains("json")
                        || !(response instanceof MockHttpServletResponse mock)) {
                    return;
                }
                try {
                    VIOLATIONS
                            .get()
                            .addAll(ApiContract.committed()
                                    .check(
                                            request.getMethod(),
                                            pattern,
                                            mock.getStatus(),
                                            type,
                                            mock.getContentAsString()));
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }
}

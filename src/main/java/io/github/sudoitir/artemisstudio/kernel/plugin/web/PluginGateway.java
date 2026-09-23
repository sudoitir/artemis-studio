package io.github.sudoitir.artemisstudio.kernel.plugin.web;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Active;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Failed;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Slot;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Updating;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one entry point for {@code /api/v1/p/{id}/**} and
 * {@code /api/v1/clusters/{clusterId}/p/{id}/**} (design.md §3, task 6.3): forwards to the active
 * runtime's own {@code DispatcherServlet}, or answers {@code 404 feature-disabled} (no such plugin
 * installed or enabled), {@code 503 plugin-updating} (a Brief-maintenance window, with
 * {@code Retry-After}) or {@code 503 plugin-failed}. The main application's own security filter
 * chain already covers {@code /api/**}, so authentication and CSRF are handled before a request
 * ever reaches here; this class only ever routes an already-authenticated call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PluginGateway implements HttpRequestHandler {

    /** Matches both gateway path shapes, capturing the plugin id that follows {@code /p/}. */
    static final Pattern PLUGIN_PATH = Pattern.compile("^/api/v1/(?:clusters/[^/]+/)?p/([^/]+)(?:/.*)?$");

    private final PluginRuntimeRegistry registry;
    private final ApplicationContext mainContext;
    private final JsonMapper json;

    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String pluginId = pluginIdOf(request.getRequestURI());
        if (pluginId == null) {
            writeProblem(response, HttpStatus.NOT_FOUND, "feature-disabled", "No plugin addressed by this path.");
            return;
        }
        Optional<Slot> slot = registry.get(pluginId);
        if (slot.isEmpty()) {
            writeProblem(
                    response,
                    HttpStatus.NOT_FOUND,
                    "feature-disabled",
                    "Plugin '" + pluginId + "' is not installed, or is disabled on this installation.");
            return;
        }
        switch (slot.get()) {
            case Active active -> forward(active.runtime(), request, response);
            case Updating updating -> {
                response.setHeader("Retry-After", String.valueOf(updating.retryAfterSeconds()));
                writeProblem(
                        response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "plugin-updating",
                        "Plugin '" + pluginId + "' is updating; retry in " + updating.retryAfterSeconds() + "s.");
            }
            case Failed failed ->
                writeProblem(
                        response,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "plugin-failed",
                        "Plugin '" + pluginId + "' failed to activate: " + failed.reason());
        }
    }

    private void forward(PluginRuntime runtime, HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // beginCall() reserves the call under the runtime's own lock; false means it lost the race
        // with a concurrent close() (drained already, or unloading right now) — the runtime looked
        // up a moment ago is gone, so this answers the same way an absent registry entry would
        // rather than forwarding into a context that may already be torn down.
        if (!runtime.beginCall()) {
            writeProblem(
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "plugin-updating",
                    "Plugin '" + runtime.id() + "' is updating; retry.");
            return;
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(runtime.classLoader());
        try {
            runtime.servlet().service(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            // The child's own HandlerExceptionResolvers (registered by PluginInfrastructure's
            // @EnableWebMvc) already had their chance inside DispatcherServlet.service(); an
            // exception reaching here means none of them resolved it, so the main application's
            // own resolver gets it next, with handler=null (there is no main-context handler for
            // this request).
            HandlerExceptionResolver mainResolver =
                    mainContext.getBean("handlerExceptionResolver", HandlerExceptionResolver.class);
            ModelAndView resolved = mainResolver.resolveException(request, response, null, e);
            if (resolved == null && !response.isCommitted()) {
                log.error("Unhandled exception forwarding to plugin '{}'", runtime.id(), e);
                writeProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "plugin-error", "The plugin call failed.");
            }
        } finally {
            runtime.endCall();
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String slug, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = Problems.of(status, slug, status.getReasonPhrase(), detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }

    private static String pluginIdOf(String requestUri) {
        Matcher matcher = PLUGIN_PATH.matcher(requestUri);
        return matcher.matches() ? matcher.group(1) : null;
    }
}

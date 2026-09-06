package io.github.sudoitir.artemisstudio.mcp;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Generates the {@code instructions} sent to a host at initialisation, from the one
 * catalogue that describes the surface (ADR-0054).
 *
 * <p>This text used to be written by hand in {@code application.yml}, and it had
 * drifted: it omitted {@code queue_lifecycle} and {@code message_body} and never
 * named the discovery route. Under a host that searches tools rather than sending
 * the whole of {@code tools/list}, the instructions are the only text guaranteed to
 * be read — so a stale copy there does not merely go out of date, it tells a model
 * that capabilities the product has do not exist. That is non-negotiable #5 broken
 * by a duplicate, and the durable fix is to keep no duplicate.
 *
 * <p>A {@link BeanPostProcessor} rather than a customizer because the server is
 * built from these properties, and under {@code protocol: STATELESS} the
 * {@code McpSyncServerCustomizer} hook does not apply. Post-processing the
 * properties bean sets the value before any consumer reads it, whatever transport
 * is configured.
 */
@Component
public class McpServerInstructions implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof McpServerProperties props) {
            props.setInstructions(McpToolCatalog.instructions());
        }
        return bean;
    }
}

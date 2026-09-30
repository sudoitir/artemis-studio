package io.github.sudoitir.artemisstudio.platform.mcp;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Puts {@link McpGate} between the servlet transport and the MCP server (ADR-0135). The
 * autoconfigured {@link WebMvcStatelessServerTransport} still serves {@code /mcp} through its router;
 * the server is handed this transport instead, whose {@code setMcpHandler} installs the server's
 * handler on the real transport wrapped in the gate. The router bean asks for the concrete type and
 * the server for the interface, which is what lets both coexist.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
class McpGateConfiguration {

    @Bean
    @Primary
    McpStatelessServerTransport mcpGatedTransport(WebMvcStatelessServerTransport transport, McpGate gate) {
        return new McpStatelessServerTransport() {
            @Override
            public void setMcpHandler(McpStatelessServerHandler handler) {
                transport.setMcpHandler(gate.wrap(handler));
            }

            @Override
            public void close() {
                transport.close();
            }

            @Override
            public Mono<Void> closeGracefully() {
                return transport.closeGracefully();
            }

            @Override
            public List<String> protocolVersions() {
                return transport.protocolVersions();
            }
        };
    }
}

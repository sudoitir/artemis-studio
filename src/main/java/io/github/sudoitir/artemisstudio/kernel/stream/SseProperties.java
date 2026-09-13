package io.github.sudoitir.artemisstudio.kernel.stream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The SSE keep-alive interval (ADR-0018). It is a setting because the value that keeps a
 * stream open is a property of whatever proxy sits in front of Studio, which the operator
 * knows and the image does not.
 */
@ConfigurationProperties(prefix = "artemis-studio.sse")
public record SseProperties(@DefaultValue("20s") Duration heartbeatInterval) {}

package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.core.ShutdownStep;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class StreamShutdownSteps {

    @Bean
    ShutdownStep streamShutdown(SseHub hub) {
        return new ShutdownStep("stream", ShutdownPhases.STREAM, hub::closeAll);
    }
}

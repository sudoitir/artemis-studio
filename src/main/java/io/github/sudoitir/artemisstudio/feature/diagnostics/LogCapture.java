package io.github.sudoitir.artemisstudio.feature.diagnostics;

import org.springframework.boot.context.event.ApplicationContextInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Attaches {@link LogRingBuffer} right after Boot has configured logging and before the startup banner lines,
 * so a bundle holds the whole start. Registered in {@code spring.factories}: it has to run before any bean.
 */
public class LogCapture implements ApplicationListener<ApplicationContextInitializedEvent> {

    @Override
    public void onApplicationEvent(ApplicationContextInitializedEvent event) {
        LogRingBuffer.attached();
    }
}

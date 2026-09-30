package io.github.sudoitir.artemisstudio.kernel.core.internal;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;

/** The log message with credential-like values masked. Registered in {@code logback-spring.xml}. */
public class RedactingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedactor.redact(super.convert(event));
    }
}

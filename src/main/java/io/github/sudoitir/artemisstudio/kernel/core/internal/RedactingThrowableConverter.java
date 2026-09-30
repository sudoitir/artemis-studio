package io.github.sudoitir.artemisstudio.kernel.core.internal;

import ch.qos.logback.classic.spi.IThrowableProxy;
import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/** Boot's stack trace with credential-like values masked. Registered in {@code logback-spring.xml}. */
public class RedactingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return SecretRedactor.redact(super.throwableProxyToString(tp));
    }
}

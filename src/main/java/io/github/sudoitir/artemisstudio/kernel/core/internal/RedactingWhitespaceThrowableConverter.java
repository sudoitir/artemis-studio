package io.github.sudoitir.artemisstudio.kernel.core.internal;

import ch.qos.logback.classic.spi.IThrowableProxy;
import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * Boot's stack trace with credential-like values masked. Boot registers its own {@code %wEx}, which wins over
 * anything registered here, so Studio's default pattern uses {@code %redactedEx} instead ({@code
 * logging.exception-conversion-word}).
 */
public class RedactingWhitespaceThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return SecretRedactor.redact(super.throwableProxyToString(tp));
    }
}

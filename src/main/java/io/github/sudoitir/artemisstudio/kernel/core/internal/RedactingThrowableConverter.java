package io.github.sudoitir.artemisstudio.kernel.core.internal;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;
import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;

/** Logback's stack trace with credential-like values masked. Registered for {@code %ex} by {@link RedactingLogging}. */
public class RedactingThrowableConverter extends ThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return SecretRedactor.redact(super.throwableProxyToString(tp));
    }
}

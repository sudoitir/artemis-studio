package io.github.sudoitir.rahdar.kernel.core.internal;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.IThrowableProxy;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;

/** Logback's stack trace with packaging data, masked. Registered for {@code %xEx} by {@link RedactingLogging}. */
public class RedactingExtendedThrowableConverter extends ExtendedThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return SecretRedactor.redact(super.throwableProxyToString(tp));
    }
}

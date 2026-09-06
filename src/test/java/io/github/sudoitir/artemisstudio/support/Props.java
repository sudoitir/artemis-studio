package io.github.sudoitir.artemisstudio.support;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.Events;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.RateLimit;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.Security;

/**
 * {@link ArtemisStudioProperties} for unit tests, which almost always want "the
 * packaged defaults, except one section" — the record's compact constructor fills
 * in every {@code null} with its default, so that is all a caller has to say.
 *
 * <p>This exists so that adding a section to the properties record is a one-line
 * change here instead of a positional {@code null} appended to a dozen unrelated
 * test files, none of which care about the new section.
 */
public final class Props {

    private Props() {}

    /** Every section at its packaged default. */
    public static ArtemisStudioProperties defaults() {
        return new ArtemisStudioProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ArtemisStudioProperties secretKey(String secretKey) {
        return new ArtemisStudioProperties(
                secretKey, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ArtemisStudioProperties rateLimit(int callsPerSecond) {
        return new ArtemisStudioProperties(
                null, null, null, new RateLimit(callsPerSecond), null, null, null, null, null, null, null, null, null);
    }

    public static ArtemisStudioProperties events(Events events) {
        return new ArtemisStudioProperties(
                null, null, null, null, null, null, null, events, null, null, null, null, null);
    }

    public static ArtemisStudioProperties security(Security security) {
        return new ArtemisStudioProperties(
                null, null, null, null, null, null, null, null, null, null, security, null, null);
    }
}

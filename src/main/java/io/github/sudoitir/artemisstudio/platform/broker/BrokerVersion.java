package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Artemis broker release, as its {@code Version} attribute reports it, and the
 * range Studio supports (ADR-0142).
 *
 * <p>{@link #MINIMUM} is the oldest release Studio's core features work on: the
 * first with the thirteen-argument {@code addSecuritySettings}, carrying the view and
 * edit permissions, that capture, plugin taps and configuration edits call. {@link #LATEST_TESTED} is the newest release CI runs
 * the integration suite against. CI runs both ends; a test keeps the pipeline and
 * these constants in step.
 */
public record BrokerVersion(int major, int minor, int patch) implements Comparable<BrokerVersion> {

    public static final BrokerVersion MINIMUM = new BrokerVersion(2, 33, 0);
    public static final BrokerVersion LATEST_TESTED = new BrokerVersion(2, 57, 0);

    /** Where a node's version sits against the supported range. */
    public enum Support {
        SUPPORTED,
        /** Older than {@link #MINIMUM}: registration is refused, a node found later is flagged. */
        BELOW_MINIMUM,
        /** Newer than {@link #LATEST_TESTED}: allowed, with a warning that it is untested. */
        NEWER_THAN_TESTED,
        /** The node has not reported a version, or reported one Studio cannot read. */
        UNKNOWN
    }

    private static final Pattern RELEASE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Comparator<BrokerVersion> ORDER = Comparator.comparingInt(BrokerVersion::major)
            .thenComparingInt(BrokerVersion::minor)
            .thenComparingInt(BrokerVersion::patch);

    /** The release in a {@code Version} attribute such as {@code 2.45.0-SNAPSHOT}; empty when there is none. */
    public static Optional<BrokerVersion> parse(String version) {
        if (version == null) {
            return Optional.empty();
        }
        Matcher m = RELEASE.matcher(version);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(new BrokerVersion(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))));
    }

    public static Support support(String version) {
        return parse(version).map(BrokerVersion::support).orElse(Support.UNKNOWN);
    }

    public Support support() {
        if (!atLeast(MINIMUM)) {
            return Support.BELOW_MINIMUM;
        }
        return compareTo(LATEST_TESTED) > 0 ? Support.NEWER_THAN_TESTED : Support.SUPPORTED;
    }

    public boolean atLeast(BrokerVersion other) {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(BrokerVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}

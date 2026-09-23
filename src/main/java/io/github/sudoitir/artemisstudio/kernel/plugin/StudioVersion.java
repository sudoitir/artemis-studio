package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The running Studio version, for a plugin's {@code studio.since}/{@code until} check
 * (ADR-0099). Read from Boot's {@code BuildProperties} (the {@code build-info} goal writes
 * {@code META-INF/build-info.properties}); a SNAPSHOT build, or one with no build-info at all
 * (an IDE run), is <b>unknown</b> — the range check is then skipped with a warning, never a
 * violation (design.md §3).
 *
 * <p>{@code artemis-studio.plugins.studio-version-override} pins the version for tests, taking
 * priority over {@code BuildProperties} when set.
 */
@Component
public class StudioVersion {

    private final Optional<CalVer> current;

    public StudioVersion(ObjectProvider<BuildProperties> buildProperties, PluginProperties properties) {
        this.current = CalVer.parse(resolveRaw(buildProperties, properties.studioVersionOverride()));
    }

    private static String resolveRaw(ObjectProvider<BuildProperties> buildProperties, String override) {
        if (StringUtils.hasText(override)) {
            return override;
        }
        BuildProperties built = buildProperties.getIfAvailable();
        return built == null ? null : built.getVersion();
    }

    public boolean isKnown() {
        return current.isPresent();
    }

    public Optional<CalVer> current() {
        return current;
    }

    /**
     * Whether a plugin declaring {@code since}..{@code until} is compatible with the running
     * Studio. {@code until} may be an exact CalVer or a {@code YYYY.MM.*} wildcard; a missing
     * {@code until} means "still current". Unknown when the running version could not be
     * determined.
     */
    public Compatibility check(String since, String until) {
        if (current.isEmpty()) {
            return Compatibility.UNKNOWN;
        }
        CalVer version = current.get();
        Optional<CalVer> sinceVersion = CalVer.parse(since);
        if (sinceVersion.isPresent() && version.compareTo(sinceVersion.get()) < 0) {
            return Compatibility.TOO_OLD;
        }
        if (until == null) {
            return Compatibility.COMPATIBLE;
        }
        Optional<CalVer> wildcard = CalVer.parseMonthWildcard(until);
        if (wildcard.isPresent()) {
            return version.sameMonthAs(wildcard.get()) || version.compareTo(wildcard.get()) <= 0
                    ? Compatibility.COMPATIBLE
                    : Compatibility.TOO_NEW;
        }
        Optional<CalVer> untilVersion = CalVer.parse(until);
        if (untilVersion.isEmpty()) {
            return Compatibility.UNKNOWN;
        }
        return version.compareTo(untilVersion.get()) <= 0 ? Compatibility.COMPATIBLE : Compatibility.TOO_NEW;
    }

    public enum Compatibility {
        COMPATIBLE,
        TOO_OLD,
        TOO_NEW,
        UNKNOWN
    }
}

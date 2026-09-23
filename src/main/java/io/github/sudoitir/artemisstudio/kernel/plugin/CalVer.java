package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A CalVer {@code YYYY.MM.PATCH} version (.claude/rules/10-release.md), or the {@code YYYY.MM.*}
 * shape a plugin's {@code studio.until} may use to mean "any patch of that month".
 */
public record CalVer(int year, int month, int patch) implements Comparable<CalVer> {

    private static final Pattern EXACT = Pattern.compile("^(\\d{4})\\.(\\d{2})\\.(\\d+)$");
    private static final Pattern MONTH_WILDCARD = Pattern.compile("^(\\d{4})\\.(\\d{2})\\.\\*$");
    private static final Comparator<CalVer> ORDER = Comparator.comparingInt(CalVer::year)
            .thenComparingInt(CalVer::month)
            .thenComparingInt(CalVer::patch);

    /** Parses an exact {@code YYYY.MM.PATCH} version. Empty when the shape does not match. */
    public static Optional<CalVer> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var m = EXACT.matcher(value.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(
                new CalVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
    }

    /**
     * Whether {@code value} is a {@code YYYY.MM.*} month wildcard, only valid for {@code until}.
     * Returns the year/month as a {@code CalVer} with {@code patch=0}, used for month-only compares.
     */
    public static Optional<CalVer> parseMonthWildcard(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var m = MONTH_WILDCARD.matcher(value.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new CalVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 0));
    }

    /** Whether {@code this} falls within the same year and month as {@code other}. */
    public boolean sameMonthAs(CalVer other) {
        return year == other.year && month == other.month;
    }

    @Override
    public int compareTo(CalVer other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return "%04d.%02d.%d".formatted(year, month, patch);
    }
}

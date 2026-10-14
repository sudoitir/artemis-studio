package io.github.sudoitir.rahdar.kernel.lifecycle.partition;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** How long one partition covers. Bounds are always UTC. */
public enum Grain {
    DAY("yyyyMMdd"),
    MONTH("yyyyMM");

    private final DateTimeFormatter suffix;

    Grain(String pattern) {
        this.suffix = DateTimeFormatter.ofPattern(pattern);
    }

    /** The first day of the period that holds {@code day}. */
    LocalDate startOf(LocalDate day) {
        return this == MONTH ? day.withDayOfMonth(1) : day;
    }

    /** The first day of the period after the one starting at {@code start}. */
    LocalDate next(LocalDate start) {
        return this == MONTH ? start.plusMonths(1) : start.plusDays(1);
    }

    /** The partition of {@code table} that starts on {@code start}: {@code <table>_p<suffix>}. */
    String name(String table, LocalDate start) {
        return table + "_p" + suffix.format(start);
    }

    /** The first day of the period a partition name of {@code table} stands for, or {@code null} when it is not one. */
    LocalDate start(String table, String name) {
        String prefix = table + "_p";
        if (!name.startsWith(prefix)
                || name.length()
                        != prefix.length()
                                + suffix.format(LocalDate.of(2000, 1, 1)).length()) {
            return null;
        }
        try {
            String digits = name.substring(prefix.length());
            return this == MONTH
                    ? LocalDate.parse(digits + "01", DateTimeFormatter.ofPattern("yyyyMMdd"))
                    : LocalDate.parse(digits, suffix);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /** A UTC timestamp literal for the start of {@code day}. */
    static String bound(LocalDate day) {
        return "'" + day.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime() + "'";
    }
}

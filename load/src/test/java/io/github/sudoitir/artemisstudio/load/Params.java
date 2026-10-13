package io.github.sudoitir.artemisstudio.load;

import java.nio.file.Path;

/** The run's parameters: system properties (`-Dload.users=20`), set by `just load` from the profile. */
final class Params {

    static final String URL = text("load.url", "http://127.0.0.1:18090");
    /** Directory with users.csv, flood.csv and clusters.csv, written by scripts/load-seed.sh. */
    static final Path SEED = Path.of(text("load.seed", "target/seed"));

    static final Path REPORT = Path.of(text("load.report", "target/report"));
    /** Seconds each steady-state scenario runs. */
    static final int DURATION = integer("load.duration", 60);
    /** Seconds over which the virtual users start. */
    static final int RAMP = integer("load.ramp", 10);
    /** Concurrent UI users reading cluster views, queue lists and metric history. */
    static final int UI_USERS = integer("load.users", 10);
    /** Concurrent event-stream subscribers that read every frame. */
    static final int SSE_FAST = integer("load.sse.fast", 10);
    /** Concurrent event-stream subscribers that open the stream and never read it. */
    static final int SSE_STALLED = integer("load.sse.stalled", 2);
    /** Concurrent audit exports by one account, to exceed its per-user limit. */
    static final int EXPORT_USERS = integer("load.exports", 3);
    /** Concurrent SQL console queries by one account, to exceed its per-user limit. */
    static final int SQL_USERS = integer("load.sql", 3);
    /** Largest queue-list page index a UI user opens; set from the seeded queue count. */
    static final int QUEUE_PAGES = integer("load.queue.pages", 5);
    /** Percent of requests allowed to fail (a refusal with 429 is not a failure). */
    static final double MAX_FAILED_PERCENT = Double.parseDouble(text("load.max-failed-percent", "1"));

    private Params() {}

    static String text(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    static int integer(String key, int fallback) {
        return Integer.parseInt(text(key, Integer.toString(fallback)));
    }
}

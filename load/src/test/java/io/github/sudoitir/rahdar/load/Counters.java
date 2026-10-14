package io.github.sudoitir.rahdar.load;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

/** What Gatling cannot show: refusals the test expects, and the stalled subscribers' fate. */
final class Counters {

    /** A heavy request refused with 429, the per-user limit doing its job. */
    static final AtomicLong REFUSED_EXPORT = new AtomicLong();

    static final AtomicLong REFUSED_SQL = new AtomicLong();
    static final AtomicLong SQL_ERROR_FRAMES = new AtomicLong();
    static final AtomicLong ACCEPTED_EXPORT = new AtomicLong();
    static final AtomicLong ACCEPTED_SQL = new AtomicLong();

    private Counters() {}

    static void write() throws IOException {
        Files.createDirectories(Params.REPORT);
        Files.writeString(
                Params.REPORT.resolve("simulation-counts.json"),
                """
                {"exportAccepted":%d,"exportRefused429":%d,"sqlAccepted":%d,"sqlRefused429":%d,"sqlErrorFrames":%d,\
                "stalledOpened":%d,"stalledClosedByServer":%d,"stalledUnreadBytes":%d}
                """
                        .formatted(
                                ACCEPTED_EXPORT.get(),
                                REFUSED_EXPORT.get(),
                                ACCEPTED_SQL.get(),
                                REFUSED_SQL.get(),
                                SQL_ERROR_FRAMES.get(),
                                StalledStreams.opened(),
                                StalledStreams.closedByServer(),
                                StalledStreams.unreadBytes()));
    }
}

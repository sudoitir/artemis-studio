package io.github.sudoitir.artemisstudio.feature.apitokens;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Hourly per-token request counters (ADR-0136): counted in memory per request and written by the
 * minute flush, so a busy token costs one upsert a minute, not a row per request.
 */
@Component
@RequiredArgsConstructor
public class TokenUsage {

    /** How a token request ended. */
    public enum Outcome {
        OK,
        DENIED,
        LIMITED,
        ERROR
    }

    public record Totals(long requests, long denied, long limited, long errors) {}

    public record Day(LocalDate day, long requests, long denied, long limited, long errors) {}

    public record Summary(int days, Totals totals, List<Day> perDay) {}

    private record Key(UUID tokenId, Instant hour) {}

    private final JdbcClient jdbc;

    /** [requests, denied, limited, errors]; mutated only inside {@code compute}, removed whole by the flush. */
    private final Map<Key, long[]> pending = new ConcurrentHashMap<>();

    public void record(UUID tokenId, Outcome outcome) {
        Key key = new Key(tokenId, Instant.now().truncatedTo(ChronoUnit.HOURS));
        pending.compute(key, (k, counts) -> {
            long[] c = counts != null ? counts : new long[4];
            c[0]++;
            if (outcome != Outcome.OK) {
                c[outcome.ordinal()]++;
            }
            return c;
        });
    }

    /** Upserts every pending bucket. A count recorded during the flush lands in a fresh bucket for the next one. */
    public void flush() {
        for (Key key : List.copyOf(pending.keySet())) {
            long[] c = pending.remove(key);
            if (c == null) {
                continue;
            }
            jdbc.sql("""
                            INSERT INTO api_token_usage (hour, requests, denied, limited, errors, token_id)
                            SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM api_token WHERE id = ?)
                            ON CONFLICT (token_id, hour) DO UPDATE SET
                                requests = api_token_usage.requests + EXCLUDED.requests,
                                denied = api_token_usage.denied + EXCLUDED.denied,
                                limited = api_token_usage.limited + EXCLUDED.limited,
                                errors = api_token_usage.errors + EXCLUDED.errors""")
                    .params(java.sql.Timestamp.from(key.hour()), c[0], c[1], c[2], c[3], key.tokenId(), key.tokenId())
                    .update();
        }
    }

    /** The last {@code days} days, today included (UTC), in total and per day, oldest first. */
    public Summary summary(UUID tokenId, int days) {
        Instant from = LocalDate.now(ZoneOffset.UTC)
                .minusDays(days - 1L)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        List<Day> perDay = jdbc.sql("""
                        SELECT (hour AT TIME ZONE 'UTC')::date AS day, sum(requests) AS requests,
                               sum(denied) AS denied, sum(limited) AS limited, sum(errors) AS errors
                        FROM api_token_usage WHERE token_id = ? AND hour >= ?
                        GROUP BY 1 ORDER BY 1""")
                .params(tokenId, java.sql.Timestamp.from(from))
                .query((rs, n) -> new Day(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("requests"),
                        rs.getLong("denied"),
                        rs.getLong("limited"),
                        rs.getLong("errors")))
                .list();
        Totals totals = new Totals(
                perDay.stream().mapToLong(Day::requests).sum(),
                perDay.stream().mapToLong(Day::denied).sum(),
                perDay.stream().mapToLong(Day::limited).sum(),
                perDay.stream().mapToLong(Day::errors).sum());
        return new Summary(days, totals, perDay);
    }
}

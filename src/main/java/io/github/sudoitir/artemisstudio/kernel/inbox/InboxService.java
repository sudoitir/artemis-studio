package io.github.sudoitir.artemisstudio.kernel.inbox;

import io.github.sudoitir.artemisstudio.kernel.inbox.Notice.Severity;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginScopedBeans;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionHolders;
import io.github.sudoitir.artemisstudio.kernel.stream.UserSignals;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The inbox: writes by source, reads by recipient. Studio's own features call it with their feature id
 * as {@code source}; a plugin gets an {@link Inbox} bound to its id instead ({@link #beansFor}), so it
 * cannot post as anyone else.
 *
 * <p>A post is one {@code INSERT ... SELECT ... FROM unnest(ids) ON CONFLICT DO UPDATE}, and signals each
 * recipient's stream inside the caller's transaction, so the bell only moves for a notice that committed.
 * Every method that changes an inbox signals the owner too, so the user's other tabs follow.
 */
@Service
@Slf4j
public class InboxService implements PluginScopedBeans {

    static final String BEAN_NAME = "inbox";

    private static final int MAX_SOURCE = 100;
    private static final TypeReference<Map<String, String>> DATA = new TypeReference<>() {};

    private static final String POST = """
            INSERT INTO inbox_item
                (recipient_id, source, kind, severity, title, body, link, dedupe_key, data, created_at, expires_at)
            SELECT r.id, ?, ?, ?, ?, ?, ?, ?, ?::json, ?, ?
            FROM unnest(?) AS r(id) JOIN app_user u ON u.id = r.id
            ON CONFLICT (recipient_id, source, dedupe_key) WHERE dedupe_key IS NOT NULL
            DO UPDATE SET kind = EXCLUDED.kind, severity = EXCLUDED.severity, title = EXCLUDED.title,
                body = EXCLUDED.body, link = EXCLUDED.link, data = EXCLUDED.data,
                created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at, read_at = NULL
            RETURNING recipient_id""";

    private static final String COLUMNS = "id, source, kind, severity, title, body, link, data, created_at, read_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final UserSignals signals;
    private final PermissionHolders holders;

    public InboxService(
            JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, UserSignals signals, PermissionHolders holders) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.signals = signals;
        this.holders = holders;
    }

    @Override
    public Map<String, Object> beansFor(String pluginId) {
        return Map.of(BEAN_NAME, new ScopedInbox(this, pluginId));
    }

    // ---- writes ------------------------------------------------------------------------------------

    @Transactional
    public int post(String source, Notice notice, Collection<UUID> recipients) {
        requireSource(source);
        Set<UUID> ids = new LinkedHashSet<>(recipients);
        if (ids.size() > Inbox.MAX_RECIPIENTS) {
            throw new IllegalArgumentException(
                    "A notice may address at most " + Inbox.MAX_RECIPIENTS + " users, not " + ids.size() + ".");
        }
        if (ids.isEmpty()) {
            return 0;
        }
        String data = json(notice.data());
        Instant now = clock.instant();
        Timestamp expires = notice.ttl() == null ? null : Timestamp.from(now.plus(notice.ttl()));
        List<UUID> posted = jdbc.query(
                con -> {
                    var ps = con.prepareStatement(POST);
                    ps.setString(1, source);
                    ps.setString(2, notice.kind());
                    ps.setString(3, notice.severity().wire());
                    ps.setString(4, notice.title());
                    ps.setString(5, notice.body());
                    ps.setString(6, notice.link());
                    ps.setString(7, notice.dedupeKey());
                    ps.setString(8, data);
                    ps.setTimestamp(9, Timestamp.from(now));
                    ps.setTimestamp(10, expires);
                    ps.setArray(11, con.createArrayOf("uuid", ids.toArray()));
                    return ps;
                },
                (rs, i) -> rs.getObject(1, UUID.class));
        posted.forEach(this::signal);
        return posted.size();
    }

    @Transactional
    public int postToHolders(
            String source, Notice notice, String permission, UUID clusterId, Collection<UUID> exclude) {
        requireSource(source);
        Set<UUID> skip = exclude == null ? Set.of() : Set.copyOf(exclude);
        List<UUID> found = holders.holders(clusterId, permission, Inbox.MAX_RECIPIENTS + skip.size() + 1).stream()
                .filter(id -> !skip.contains(id))
                .toList();
        if (found.size() > Inbox.MAX_RECIPIENTS) {
            log.warn(
                    "{} users hold '{}'; the '{}' notice from {} reaches the first {}",
                    found.size(),
                    permission,
                    notice.kind(),
                    source,
                    Inbox.MAX_RECIPIENTS);
            found = found.subList(0, Inbox.MAX_RECIPIENTS);
        }
        return post(source, notice, found);
    }

    @Transactional
    public int resolve(String source, String dedupeKey, String newTitle) {
        requireSource(source);
        if (dedupeKey == null || dedupeKey.isEmpty()) {
            throw new IllegalArgumentException("resolve needs the dedupe key of what was posted.");
        }
        if (newTitle != null && (newTitle.isBlank() || newTitle.length() > Notice.MAX_TITLE)) {
            throw new IllegalArgumentException("A notice's title must be 1 to " + Notice.MAX_TITLE + " characters.");
        }
        List<UUID> changed = jdbc.query(
                "UPDATE inbox_item SET read_at = ?, title = coalesce(?, title)"
                        + " WHERE source = ? AND dedupe_key = ? AND read_at IS NULL RETURNING recipient_id",
                (rs, i) -> rs.getObject(1, UUID.class),
                Timestamp.from(clock.instant()),
                newTitle,
                source,
                dedupeKey);
        changed.forEach(this::signal);
        return changed.size();
    }

    // ---- reads and the recipient's own changes -----------------------------------------------------

    /** A page of the user's notices, newest first, of those with an id below {@code before} when given. */
    @Transactional(readOnly = true)
    public Page list(UUID userId, boolean unreadOnly, Long before, int limit) {
        List<InboxItem> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM inbox_item WHERE recipient_id = ?"
                        + (unreadOnly ? " AND read_at IS NULL" : "")
                        + (before == null ? "" : " AND id < ?")
                        + " ORDER BY id DESC LIMIT ?",
                this::item,
                before == null ? new Object[] {userId, limit + 1} : new Object[] {userId, before, limit + 1});
        if (rows.size() <= limit) {
            return new Page(rows, null);
        }
        List<InboxItem> items = rows.subList(0, limit);
        return new Page(items, items.getLast().id());
    }

    /** The user's unread notices, counted only up to {@value #COUNT_CAP} so the count stays one index scan. */
    @Transactional(readOnly = true)
    public Count count(UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM inbox_item WHERE recipient_id = ? AND read_at IS NULL LIMIT "
                        + COUNT_CAP + ") unread",
                Integer.class,
                userId);
        int unread = n == null ? 0 : n;
        return new Count(unread, unread >= COUNT_CAP);
    }

    @Transactional
    public int markRead(UUID userId, Collection<Long> ids) {
        Long[] array = ids.toArray(Long[]::new);
        int n = jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE inbox_item SET read_at = ? WHERE recipient_id = ? AND read_at IS NULL"
                    + " AND id = ANY(?)");
            ps.setTimestamp(1, Timestamp.from(clock.instant()));
            ps.setObject(2, userId);
            ps.setArray(3, con.createArrayOf("bigint", array));
            return ps;
        });
        signalIf(n, userId);
        return n;
    }

    @Transactional
    public int markReadUpTo(UUID userId, long upTo) {
        int n = jdbc.update(
                "UPDATE inbox_item SET read_at = ? WHERE recipient_id = ? AND read_at IS NULL AND id <= ?",
                Timestamp.from(clock.instant()),
                userId,
                upTo);
        signalIf(n, userId);
        return n;
    }

    /** @return false when the user has no such notice */
    @Transactional
    public boolean delete(UUID userId, long id) {
        boolean deleted = jdbc.update("DELETE FROM inbox_item WHERE recipient_id = ? AND id = ?", userId, id) > 0;
        signalIf(deleted ? 1 : 0, userId);
        return deleted;
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** A page of notices; {@code next} is the {@code before} of the following page, null on the last. */
    public record Page(List<InboxItem> items, Long next) {}

    /** Unread notices, up to {@value #COUNT_CAP}; {@code capped} says there are at least that many. */
    public record Count(int unread, boolean capped) {}

    static final int COUNT_CAP = 100;

    private void signalIf(int changed, UUID userId) {
        if (changed > 0) {
            signal(userId);
        }
    }

    private void signal(UUID userId) {
        signals.signal(UserSignals.INBOX, userId);
    }

    private static void requireSource(String source) {
        if (source == null || source.isBlank() || source.length() > MAX_SOURCE) {
            throw new IllegalArgumentException("A notice's source must be 1 to " + MAX_SOURCE + " characters.");
        }
    }

    /** The notice's data as stored, validated against the size the table accepts. */
    String json(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        String text = mapper.writeValueAsString(data);
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Notice.MAX_DATA_BYTES) {
            throw new IllegalArgumentException("A notice's data is larger than " + Notice.MAX_DATA_BYTES + " bytes.");
        }
        return text;
    }

    private InboxItem item(ResultSet rs, int row) throws SQLException {
        String data = rs.getString("data");
        Timestamp read = rs.getTimestamp("read_at");
        return new InboxItem(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("kind"),
                Severity.valueOf(rs.getString("severity").toUpperCase(java.util.Locale.ROOT)),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("link"),
                data == null ? null : mapper.readValue(data, DATA),
                rs.getTimestamp("created_at").toInstant(),
                read == null ? null : read.toInstant());
    }
}

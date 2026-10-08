package io.github.sudoitir.artemisstudio.kernel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.inbox.Notice.Severity;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionHolders;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.stream.UserSignals;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The inbox over real HTTP against PostgreSQL: posting and de-duplicating, paging, counting, reading and
 * deleting only one's own notices, the link and size checks of the table, retention, and live delivery of
 * a signal through the bus (pg_notify) to an open {@code /me/stream}. One replica here; the bus delivers to
 * the sender like to any other replica, so the path is the same one a second replica would take.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboxIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;

    @Autowired
    InboxService inbox;

    @Autowired
    InboxStore store;

    @Autowired
    AppUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @MockitoBean
    PermissionHolders holders;

    private record Person(UUID id, String username) {}

    private Person newUser() {
        String username = "inbox-" + UUID.randomUUID().toString().substring(0, 8);
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        return new Person(users.save(user).getId(), username);
    }

    private static Notice notice(String title, String dedupeKey) {
        return new Notice("approval", Severity.INFO, title, "body", "/approvals/1", dedupeKey, Map.of("k", "v"), null);
    }

    private final class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser signIn(Person person) throws Exception {
            send("GET", "/api/v1/auth/providers", null);
            int status = send(
                            "POST",
                            "/api/v1/auth/login",
                            "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(person.username(), PASSWORD))
                    .statusCode();
            assertThat(status).isEqualTo(200);
            return this;
        }

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            String xsrf = cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(
                            method,
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body))
                    .header("X-XSRF-TOKEN", xsrf);
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            return http.send(request.build(), BodyHandlers.ofString());
        }

        /** Opens {@code /me/stream}; the lines it receives accumulate in the returned list. */
        /** The line {@link #openStream} adds once the server ended the stream. */
        static final String ENDED = "<ended>";

        List<String> openStream() throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me/stream"))
                    .build();
            var response = http.send(request, BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            List<String> lines = new CopyOnWriteArrayList<>();
            Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                    lines.add(ENDED);
                } catch (IOException _) {
                    // closed
                }
            });
            await("the greeting").atMost(Duration.ofSeconds(5)).until(() -> lines.contains("event:ping"));
            return lines;
        }

        List<Long> ids(boolean unread) throws Exception {
            String body = send("GET", "/api/v1/inbox?limit=100" + (unread ? "&unread=true" : ""), null)
                    .body();
            return JsonPath.<List<Number>>read(body, "$.items[*].id").stream()
                    .map(Number::longValue)
                    .toList();
        }
    }

    private List<UUID> one(Person p) {
        return List.of(p.id());
    }

    @Test
    void postedNoticeIsListedForItsRecipientOnly() throws Exception {
        Person alice = newUser();
        Person bob = newUser();

        assertThat(inbox.post("approval", notice("Needs you", null), one(alice)))
                .isEqualTo(1);

        Browser a = new Browser().signIn(alice);
        var body = a.send("GET", "/api/v1/inbox", null).body();
        assertThat(JsonPath.<String>read(body, "$.items[0].title")).isEqualTo("Needs you");
        assertThat(JsonPath.<String>read(body, "$.items[0].source")).isEqualTo("approval");
        assertThat(JsonPath.<String>read(body, "$.items[0].severity")).isEqualTo("info");
        assertThat(JsonPath.<String>read(body, "$.items[0].data.k")).isEqualTo("v");
        assertThat(new Browser().signIn(bob).ids(false)).isEmpty();
    }

    @Test
    void aNoticeWithTheSameKeyReplacesTheEarlierOneAndIsUnreadAgain() throws Exception {
        Person alice = newUser();
        Browser a = new Browser().signIn(alice);
        inbox.post("approval", notice("First", "req-1"), one(alice));
        long id = a.ids(false).getFirst();
        a.send("POST", "/api/v1/inbox/read", "{\"ids\":[" + id + "]}");
        assertThat(a.ids(true)).isEmpty();

        inbox.post("approval", notice("Again", "req-1"), one(alice));
        inbox.post("other", notice("Same key, other source", "req-1"), one(alice));

        assertThat(a.ids(false)).hasSize(2);
        assertThat(a.ids(true)).hasSize(2).contains(id);
        assertThat(jdbc.queryForObject("SELECT title FROM inbox_item WHERE id = ?", String.class, id))
                .isEqualTo("Again");
    }

    @Test
    void listPagesNewestFirstAndFiltersUnread() throws Exception {
        Person alice = newUser();
        Browser a = new Browser().signIn(alice);
        for (int i = 0; i < 5; i++) {
            inbox.post("approval", notice("n" + i, null), one(alice));
        }
        List<Long> all = a.ids(false);
        assertThat(all).isSortedAccordingTo(java.util.Comparator.reverseOrder()).hasSize(5);
        a.send("POST", "/api/v1/inbox/read", "{\"ids\":[" + all.get(0) + "]}");

        var first = a.send("GET", "/api/v1/inbox?limit=2", null).body();
        long next = JsonPath.<Number>read(first, "$.next").longValue();
        var second = a.send("GET", "/api/v1/inbox?limit=2&before=" + next, null).body();

        assertThat(JsonPath.<List<Number>>read(first, "$.items[*].id"))
                .extracting(Number::longValue)
                .containsExactly(all.get(0), all.get(1));
        assertThat(JsonPath.<List<Number>>read(second, "$.items[*].id"))
                .extracting(Number::longValue)
                .containsExactly(all.get(2), all.get(3));
        assertThat(a.ids(true)).containsExactlyElementsOf(all.subList(1, 5));
        assertThat(a.send("GET", "/api/v1/inbox?limit=101", null).statusCode()).isEqualTo(400);
    }

    @Test
    void theUnreadCountIsBounded() throws Exception {
        Person alice = newUser();
        Browser a = new Browser().signIn(alice);
        for (int i = 0; i < 3; i++) {
            inbox.post("approval", notice("n" + i, null), one(alice));
        }
        var few = a.send("GET", "/api/v1/inbox/count", null).body();
        assertThat(JsonPath.<Integer>read(few, "$.unread")).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(few, "$.capped")).isFalse();

        jdbc.update(
                "INSERT INTO inbox_item (recipient_id, source, kind, severity, title, created_at)"
                        + " SELECT ?, 'bulk', 'k', 'info', 't', now() FROM generate_series(1, 150)",
                alice.id());

        var many = a.send("GET", "/api/v1/inbox/count", null).body();
        assertThat(JsonPath.<Integer>read(many, "$.unread")).isEqualTo(100);
        assertThat(JsonPath.<Boolean>read(many, "$.capped")).isTrue();
    }

    @Test
    void aUserCannotReadOrDeleteAnotherUsersNotice() throws Exception {
        Person alice = newUser();
        Person bob = newUser();
        inbox.post("approval", notice("Alice only", null), one(alice));
        long id = new Browser().signIn(alice).ids(false).getFirst();
        Browser b = new Browser().signIn(bob);

        assertThat(b.send("DELETE", "/api/v1/inbox/" + id, null).statusCode()).isEqualTo(404);
        assertThat(JsonPath.<Integer>read(
                        b.send("POST", "/api/v1/inbox/read", "{\"ids\":[" + id + "]}")
                                .body(),
                        "$.updated"))
                .isZero();
        assertThat(JsonPath.<Integer>read(
                        b.send("POST", "/api/v1/inbox/read", "{\"upTo\":" + id + "}")
                                .body(),
                        "$.updated"))
                .isZero();

        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM inbox_item WHERE id = ?", Boolean.class, id))
                .isTrue();
        Browser a = new Browser().signIn(alice);
        assertThat(a.send("DELETE", "/api/v1/inbox/" + id, null).statusCode()).isEqualTo(204);
        assertThat(a.send("DELETE", "/api/v1/inbox/" + id, null).statusCode()).isEqualTo(404);
    }

    @Test
    void readNeedsExactlyOneOfIdsAndUpTo() throws Exception {
        Browser a = new Browser().signIn(newUser());

        assertThat(a.send("POST", "/api/v1/inbox/read", "{}").statusCode()).isEqualTo(400);
        assertThat(a.send("POST", "/api/v1/inbox/read", "{\"ids\":[1],\"upTo\":2}")
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void markingReadUpToLeavesLaterNoticesUnread() throws Exception {
        Person alice = newUser();
        Browser a = new Browser().signIn(alice);
        for (int i = 0; i < 3; i++) {
            inbox.post("approval", notice("n" + i, null), one(alice));
        }
        List<Long> ids = a.ids(false);

        a.send("POST", "/api/v1/inbox/read", "{\"upTo\":" + ids.get(1) + "}");

        assertThat(a.ids(true)).containsExactly(ids.get(0));
    }

    @Test
    void theTableRefusesAnExternalLinkAndOversizedFields() {
        Person alice = newUser();
        for (String link : List.of("https://evil.example", "//evil.example", "/a\\b", "/a//b", "x", "/")) {
            assertThatThrownBy(() -> insertRaw(alice, "t", link, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insertRaw(alice, "t".repeat(201), null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(alice, "t", null, "{\"a\":\"" + "x".repeat(4100) + "\"}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertRaw(alice, "t", "/approvals/1", "{\"a\":\"b\"}");
    }

    private void insertRaw(Person to, String title, String link, String data) {
        jdbc.update(
                "INSERT INTO inbox_item (recipient_id, source, kind, severity, title, link, data, created_at)"
                        + " VALUES (?, 's', 'k', 'info', ?, ?, ?::json, now())",
                to.id(),
                title,
                link,
                data);
    }

    @Test
    void severityIsCheckedByTheTable() {
        Person alice = newUser();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO inbox_item (recipient_id, source, kind, severity, title, created_at)"
                                + " VALUES (?, 's', 'k', 'fatal', 't', now())",
                        alice.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void moreThanFiveHundredRecipientsAreRefused() {
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i <= Inbox.MAX_RECIPIENTS; i++) {
            many.add(UUID.randomUUID());
        }

        assertThatThrownBy(() -> inbox.post("approval", notice("t", null), many))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    void unknownRecipientsAreSkipped() {
        Person alice = newUser();

        assertThat(inbox.post("approval", notice("t", null), List.of(alice.id(), UUID.randomUUID(), alice.id())))
                .isEqualTo(1);
    }

    @Test
    void postToHoldersSkipsTheExcludedUsers() {
        Person alice = newUser();
        Person bob = newUser();
        UUID cluster = UUID.randomUUID();
        when(holders.holders(eq(cluster), eq("approval.decide"), anyInt())).thenReturn(List.of(alice.id(), bob.id()));

        int posted = inbox.postToHolders("approval", notice("t", null), "approval.decide", cluster, List.of(bob.id()));

        assertThat(posted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_item WHERE recipient_id = ?", Long.class, bob.id()))
                .isZero();
    }

    @Test
    void resolveReadsAndRetitlesWhatWasPostedUnderTheKey() {
        Person alice = newUser();
        Person bob = newUser();
        inbox.post("approval", notice("Needs a decision", "req-9"), List.of(alice.id(), bob.id()));

        assertThat(inbox.resolve("approval", "req-9", "Approved by carol")).isEqualTo(2);
        assertThat(inbox.resolve("approval", "req-9", "Approved by carol")).isZero();

        assertThat(jdbc.queryForObject("SELECT title FROM inbox_item WHERE recipient_id = ?", String.class, bob.id()))
                .isEqualTo("Approved by carol");
        assertThat(inbox.count(bob.id()).unread()).isZero();
    }

    @Test
    void aPluginPostsUnderItsOwnIdOnly() {
        Person alice = newUser();
        Inbox scoped = (Inbox) inbox.beansFor("my-plugin").get("inbox");

        scoped.post(notice("From a plugin", "k"), one(alice));

        assertThat(jdbc.queryForObject(
                        "SELECT source FROM inbox_item WHERE recipient_id = ?", String.class, alice.id()))
                .isEqualTo("my-plugin");
        assertThat(scoped.resolve("k", null)).isEqualTo(1);
    }

    @Test
    void retentionRemovesOldReadAndExpiredNoticesOnly() {
        Person alice = newUser();
        for (String title : List.of("old", "read-long-ago", "read-recently", "expired", "fresh")) {
            inbox.post("approval", notice(title, null), one(alice));
        }
        age("old", "created_at = now() - interval '91 days'");
        age("read-long-ago", "read_at = now() - interval '31 days'");
        age("read-recently", "read_at = now() - interval '1 day'");
        age("expired", "expires_at = now() - interval '1 minute'");

        long removed = store.purgeBatch(Instant.now().minus(Duration.ofDays(90)), 100);

        assertThat(removed).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForList("SELECT title FROM inbox_item WHERE recipient_id = ?", String.class, alice.id()))
                .containsExactlyInAnyOrder("read-recently", "fresh");
    }

    private void age(String title, String assignment) {
        jdbc.update("UPDATE inbox_item SET " + assignment + " WHERE title = ?", title);
    }

    @Test
    void aPostedNoticeNudgesTheRecipientsOpenStreamThroughTheBus() throws Exception {
        Person alice = newUser();
        Person bob = newUser();
        List<String> aliceLines = new Browser().signIn(alice).openStream();
        List<String> bobLines = new Browser().signIn(bob).openStream();

        tx.executeWithoutResult(s -> inbox.post("approval", notice("Live", null), one(alice)));

        await("the inbox event").atMost(Duration.ofSeconds(5)).until(() -> aliceLines.contains("event:inbox"));
        assertThat(bobLines).doesNotContain("event:inbox");
    }

    @Test
    void aRolledBackPostSendsNothing() throws Exception {
        Person alice = newUser();
        List<String> lines = new Browser().signIn(alice).openStream();

        tx.executeWithoutResult(s -> {
            inbox.post("approval", notice("Never", null), one(alice));
            s.setRollbackOnly();
        });

        Thread.sleep(500);
        assertThat(lines).doesNotContain("event:inbox");
    }

    @Test
    void readingInOneTabNudgesTheOthers() throws Exception {
        Person alice = newUser();
        Browser tab = new Browser().signIn(alice);
        inbox.post("approval", notice("n", null), one(alice));
        List<String> lines = new Browser().signIn(alice).openStream();

        tab.send("POST", "/api/v1/inbox/read", "{\"upTo\":" + tab.ids(false).getFirst() + "}");

        await("the inbox event").atMost(Duration.ofSeconds(5)).until(() -> lines.contains("event:inbox"));
    }

    @Test
    void aSixthStreamOfOneUserEndsTheOldest() throws Exception {
        Person alice = newUser();
        List<List<String>> streams = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            streams.add(new Browser().signIn(alice).openStream());
        }

        new Browser().signIn(alice).openStream();

        await("the oldest stream ends")
                .atMost(Duration.ofSeconds(5))
                .until(() -> streams.getFirst().contains(Browser.ENDED));
        assertThat(streams.subList(1, 5)).noneMatch(lines -> lines.contains(Browser.ENDED));
    }

    @Test
    void userSignalsRefuseAKindThatIsNoUserKind(@Autowired UserSignals signals) {
        assertThatThrownBy(() -> signals.signal("session-ended", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

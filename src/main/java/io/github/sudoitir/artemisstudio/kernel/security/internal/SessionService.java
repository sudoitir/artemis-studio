package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.AdministrationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.SessionViews.AccountSessionView;
import io.github.sudoitir.artemisstudio.kernel.security.web.SessionViews.EndedSessionsView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists and ends a user's sessions (ADR-0144), through Spring Session's principal-name index. A
 * session is named by a handle, the first 32 hex characters of the SHA-256 of its identifier, so
 * the identifier itself, which is the credential, is never handed out. Ending the caller's own
 * current session is signing out. Ending another is audited as {@code SESSION_END}, naming the
 * caller as actor and the session's user as target.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final int HANDLE_BYTES = 16;

    private final FindByIndexNameSessionRepository<? extends Session> store;
    private final SessionAuthentication sessions;
    private final LoginService logins;
    private final AppUserRepository users;
    private final AdministrationAudit audit;

    /** The handle of a session identifier. */
    public static String handle(String sessionId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, HANDLE_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
        }
    }

    public List<AccountSessionView> listOwn(String username, HttpServletRequest request) {
        return list(username, request);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    public List<AccountSessionView> listOf(UUID userId, HttpServletRequest request) {
        return list(usernameOf(userId), request);
    }

    @Transactional
    public void endOwn(String username, String handle, HttpServletRequest request, HttpServletResponse response) {
        end(username, handle, request, response);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public void endOf(UUID userId, String handle, HttpServletRequest request, HttpServletResponse response) {
        end(usernameOf(userId), handle, request, response);
    }

    /** Every session of the caller except the one making the request. */
    @Transactional
    public EndedSessionsView endOtherOwn(String username, HttpServletRequest request) {
        return endAllExceptCurrent(username, request);
    }

    /** Every session of the user, except the one making the request when it is theirs. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public EndedSessionsView endAllOf(UUID userId, HttpServletRequest request) {
        return endAllExceptCurrent(usernameOf(userId), request);
    }

    private List<AccountSessionView> list(String username, HttpServletRequest request) {
        String currentId = currentId(request);
        return store.findByPrincipalName(username).entrySet().stream()
                .map(entry ->
                        view(entry.getKey(), entry.getValue(), entry.getKey().equals(currentId), request))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(AccountSessionView::current)
                        .reversed()
                        .thenComparing(AccountSessionView::lastActivityAt, Comparator.reverseOrder()))
                .toList();
    }

    /** A session that has no facts is one the next request ends anyway, so it is not listed. */
    private AccountSessionView view(String id, Session stored, boolean current, HttpServletRequest request) {
        // The current session is read from the request, which has the activity the store has not seen yet.
        SessionFacts facts = current
                ? sessions.facts(request).orElse(null)
                : stored.<SessionFacts>getAttribute(SessionAuthentication.FACTS);
        if (facts == null) {
            return null;
        }
        Instant lastActivityAt =
                current ? sessions.lastActivityAt(request).orElse(facts.signedInAt()) : lastActivityOf(stored, facts);
        return new AccountSessionView(
                handle(id), facts.signedInAt(), lastActivityAt, facts.clientAddress(), facts.userAgent(), current);
    }

    private static Instant lastActivityOf(Session stored, SessionFacts facts) {
        Instant last = stored.getAttribute(SessionAuthentication.LAST_ACTIVITY_AT);
        return last != null ? last : facts.signedInAt();
    }

    private void end(String username, String handle, HttpServletRequest request, HttpServletResponse response) {
        if (handle.equals(handle(currentId(request)))) {
            logins.logout(request, response);
            return;
        }
        String id = store.findByPrincipalName(username).keySet().stream()
                .filter(candidate -> handle(candidate).equals(handle))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("session", handle));
        store.deleteById(id);
        audit.changed("SESSION_END", "user", username, Map.of("handle", handle));
    }

    private EndedSessionsView endAllExceptCurrent(String username, HttpServletRequest request) {
        String currentId = currentId(request);
        List<String> ids = store.findByPrincipalName(username).keySet().stream()
                .filter(id -> !id.equals(currentId))
                .toList();
        for (String id : ids) {
            store.deleteById(id);
            audit.changed("SESSION_END", "user", username, Map.of("handle", handle(id)));
        }
        return new EndedSessionsView(ids.size());
    }

    private static String currentId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? "" : session.getId();
    }

    private String usernameOf(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId))
                .getUsername();
    }
}

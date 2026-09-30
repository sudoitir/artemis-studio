package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.SessionViews.AccountSessionView;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

/**
 * Spring Session's per-user lookup reads sessions and their attributes with one unordered join, and on
 * PostgreSQL a session whose rows interleave with another's comes back missing attributes. The list reads
 * each session by its own id, so such a session is still listed.
 */
class SessionServiceListTest {

    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<MapSession> store = mock(FindByIndexNameSessionRepository.class);

    private final SessionService service = new SessionService(
            store,
            mock(SessionAuthentication.class),
            mock(SessionTerminator.class),
            mock(LoginService.class),
            mock(AppUserRepository.class),
            mock(io.github.sudoitir.artemisstudio.kernel.security.AdministrationAudit.class));

    @Test
    void aSessionTheUserLookupReturnsWithoutItsFactsIsStillListed() {
        SessionFacts facts = SessionFacts.signedIn(new MockHttpServletRequest("POST", "/"));
        MapSession whole = new MapSession("whole");
        whole.setAttribute(SessionAuthentication.FACTS_ATTRIBUTE, facts);
        MapSession fragment = new MapSession("whole"); // what the interleaved read returns: no attributes
        when(store.findByPrincipalName("alice")).thenReturn(Map.of("whole", fragment));
        when(store.findById("whole")).thenReturn(whole);

        java.util.List<AccountSessionView> listed = service.listOwn("alice", new MockHttpServletRequest());

        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().handle()).isEqualTo(SessionService.handle("whole"));
    }
}

package io.github.sudoitir.artemisstudio.support;

import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A stand-in for a signed-in browser session: the given authentication, in a session that carries the
 * facts every real sign-in records. {@code SessionLifetimeFilter} answers a principal whose session has
 * none as signed out, so a test that installs only the authentication would get 401.
 */
public final class SignedInSession {

    private SignedInSession() {}

    /** Use in place of {@code SecurityMockMvcRequestPostProcessors.authentication}. */
    public static RequestPostProcessor authentication(Authentication authentication) {
        RequestPostProcessor authenticated = SecurityMockMvcRequestPostProcessors.authentication(authentication);
        return request -> {
            var processed = authenticated.postProcessRequest(request);
            processed
                    .getSession()
                    .setAttribute(SessionAuthentication.FACTS_ATTRIBUTE, SessionFacts.signedIn(processed));
            return processed;
        };
    }
}

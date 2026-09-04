package io.github.sudoitir.artemisstudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ActorResolverTest {

    private final ActorResolver resolver = new ActorResolver();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void bind(HttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void anonymousWhenNoPrincipal() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.1.2.3");
        bind(req);

        Actor actor = resolver.resolve();

        assertThat(actor.username()).isEqualTo("anonymous");
        assertThat(actor.sourceIp()).isEqualTo("10.1.2.3");
        assertThat(actor.userId()).isNull();
    }

    @Test
    void preservesInboundRequestIdElseGeneratesOne() {
        MockHttpServletRequest with = new MockHttpServletRequest();
        with.addHeader("X-Request-Id", "req-abc-123");
        bind(with);
        assertThat(resolver.resolve().requestId()).isEqualTo("req-abc-123");

        RequestContextHolder.resetRequestAttributes();
        bind(new MockHttpServletRequest());
        String generated = resolver.resolve().requestId();
        assertThat(generated).isNotBlank().hasSize(36); // a UUID
    }

    @Test
    void systemActorForTheScheduler() {
        assertThat(resolver.system().username()).isEqualTo("system");
        assertThat(Actor.system().username()).isEqualTo("system");
    }
}

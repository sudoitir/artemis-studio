package io.github.sudoitir.artemisstudio.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.security.GrantLoader;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * JIT provisioning and login-time role reconciliation, isolated from the real
 * authorization-code exchange (task 9.7) — a stubbed {@link OidcUser} stands
 * in for what Spring Security hands back after that exchange completes;
 * the exchange itself is not end-to-end tested (design.md's OIDC deviation
 * note — no test IdP is practical here).
 */
@ExtendWith(MockitoExtension.class)
class StudioOidcUserServiceTest {

    @Mock
    AppUserRepository users;

    @Mock
    GrantLoader grantLoader;

    @Mock
    ClaimRoleMapper claimRoleMapper;

    @Mock
    AuditService audit;

    StudioOidcUserService service;

    @BeforeEach
    void setUp() {
        service = new StudioOidcUserService(users, grantLoader, claimRoleMapper, audit);
        when(audit.begin(any(), any(), any(), any(), any(), any(), anyMap(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(mock(AuditEventEntity.class));
    }

    private static OidcUser oidcUser(String subject, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new java.util.HashMap<>(extraClaims);
        claims.put("sub", subject);
        OidcIdToken idToken =
                new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    }

    private static AppUserEntity persistedUser(UUID id, String username) {
        AppUserEntity user = AppUserEntity.oidc(username, null, "https://idp.example", "subject-1");
        setId(user, id);
        return user;
    }

    private static void setId(AppUserEntity user, UUID id) {
        try {
            Field field = AppUserEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void firstLoginProvisionsANewUser() {
        OidcUser oidcUser = oidcUser("subject-1", Map.of("preferred_username", "alice", "groups", List.of("eng")));
        UUID newId = UUID.randomUUID();
        when(users.findByIssuerAndSubject("https://idp.example", "subject-1")).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> {
            AppUserEntity saved = inv.getArgument(0);
            setId(saved, newId);
            return saved;
        });
        when(claimRoleMapper.apply(eq(newId), anyMap())).thenReturn(ClaimRoleMapper.Outcome.MAPPED);
        when(grantLoader.loadFor(newId)).thenReturn(Set.of());

        StudioOidcUserService.Result result = service.provisionAndAuthenticate(oidcUser, "https://idp.example");

        assertThat(result).isInstanceOf(StudioOidcUserService.Result.Authenticated.class);
        var authenticated = (StudioOidcUserService.Result.Authenticated) result;
        assertThat(authenticated.principal().getUsername()).isEqualTo("alice");
        verify(users)
                .save(argThat(saved -> saved.getUsername().equals("alice")
                        && "https://idp.example".equals(saved.getIssuer())
                        && "subject-1".equals(saved.getSubject())
                        && saved.getPasswordHash() == null));
    }

    @Test
    void returningUserIsNotReProvisioned() {
        UUID existingId = UUID.randomUUID();
        OidcUser oidcUser = oidcUser("subject-1", Map.of("preferred_username", "alice"));
        when(users.findByIssuerAndSubject("https://idp.example", "subject-1"))
                .thenReturn(Optional.of(persistedUser(existingId, "alice")));
        when(claimRoleMapper.apply(eq(existingId), anyMap())).thenReturn(ClaimRoleMapper.Outcome.MAPPED);
        when(grantLoader.loadFor(existingId)).thenReturn(Set.of());

        StudioOidcUserService.Result result = service.provisionAndAuthenticate(oidcUser, "https://idp.example");

        assertThat(result).isInstanceOf(StudioOidcUserService.Result.Authenticated.class);
        verify(users, never()).save(any());
    }

    @Test
    void fallsBackToEmailWhenNoPreferredUsernameClaim() {
        OidcUser oidcUser = oidcUser("subject-2", Map.of("email", "bob@example.test"));
        UUID newId = UUID.randomUUID();
        when(users.findByIssuerAndSubject("https://idp.example", "subject-2")).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> {
            AppUserEntity saved = inv.getArgument(0);
            setId(saved, newId);
            return saved;
        });
        when(claimRoleMapper.apply(eq(newId), anyMap())).thenReturn(ClaimRoleMapper.Outcome.DEFAULTED);
        when(grantLoader.loadFor(newId)).thenReturn(Set.of());

        service.provisionAndAuthenticate(oidcUser, "https://idp.example");

        verify(users).save(argThat(saved -> saved.getUsername().equals("bob@example.test")));
    }

    @Test
    void refusesWhenClaimRoleMapperRefuses() {
        UUID existingId = UUID.randomUUID();
        OidcUser oidcUser = oidcUser("subject-1", Map.of("groups", List.of("unmapped-group")));
        when(users.findByIssuerAndSubject("https://idp.example", "subject-1"))
                .thenReturn(Optional.of(persistedUser(existingId, "alice")));
        when(claimRoleMapper.apply(eq(existingId), anyMap())).thenReturn(ClaimRoleMapper.Outcome.REFUSED);

        StudioOidcUserService.Result result = service.provisionAndAuthenticate(oidcUser, "https://idp.example");

        assertThat(result).isInstanceOf(StudioOidcUserService.Result.Refused.class);
        assertThat(((StudioOidcUserService.Result.Refused) result).username()).isEqualTo("alice");
        verify(grantLoader, never()).loadFor(any());
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static <T> T argThat(java.util.function.Predicate<T> predicate) {
        return org.mockito.ArgumentMatchers.argThat(predicate::test);
    }
}

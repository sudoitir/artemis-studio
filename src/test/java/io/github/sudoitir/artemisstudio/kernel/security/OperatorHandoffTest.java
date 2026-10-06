package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.OperatorFixture;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

/** Work handed to another thread acts as the operator who started it, and only while they still may (ADR-0093). */
class OperatorHandoffTest extends PostgresIntegrationTest {

    @Autowired
    OperatorHandoff handoff;

    @Autowired
    ActorResolver actors;

    @Autowired
    PermissionResolver perm;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    AccessChanges accessChanges;

    @Autowired
    GrantLoader grants;

    private UUID userId;

    @BeforeEach
    void signIn() {
        userId = OperatorFixture.signIn(users, roles, rolePermissions, userRoles, grants);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anotherThreadActsAsTheCapturedOperator() throws Exception {
        Operator operator = handoff.capture();
        AtomicReference<Actor> actor = new AtomicReference<>();
        AtomicReference<Boolean> can = new AtomicReference<>();

        Thread.ofVirtual()
                .start(() -> handoff.runAs(operator, () -> {
                    actor.set(actors.resolve());
                    can.set(perm.can(null, "queue:delete"));
                }))
                .join();

        assertThat(actor.get().userId()).isEqualTo(userId);
        assertThat(actor.get().requestId()).isEqualTo(operator.actor().requestId());
        assertThat(can.get()).isTrue();
    }

    @Test
    void aWithdrawnGrantIsNoLongerHeld() {
        Operator operator = handoff.capture();
        assertThat(handoff.stillHolds(operator, null, "queue:delete")).isTrue();

        OperatorFixture.revokeAll(userRoles, accessChanges, userId);

        assertThat(handoff.stillHolds(operator, null, "queue:delete")).isFalse();
    }

    @Test
    void aWithdrawnGrantIsNoLongerHeldOnAQueueEither() {
        Operator operator = handoff.capture();
        ResourceRef queue = ResourceRef.queue("orders.in");
        assertThat(handoff.stillHolds(operator, UUID.randomUUID(), queue, "queue:purge"))
                .isTrue();

        OperatorFixture.revokeAll(userRoles, accessChanges, userId);

        assertThat(handoff.stillHolds(operator, UUID.randomUUID(), queue, "queue:purge"))
                .isFalse();
    }

    @Test
    void aUserIsActedForAsTheirAccountStandsNow() {
        Operator operator = handoff.forUser(userId).orElseThrow();

        assertThat(operator.actor().userId()).isEqualTo(userId);
        assertThat(handoff.stillHolds(operator, null, "queue:delete")).isTrue();

        OperatorFixture.revokeAll(userRoles, accessChanges, userId);

        assertThat(handoff.forUser(userId).orElseThrow().principal().getAuthorities())
                .isEmpty();
    }

    @Test
    void noOperatorForANullUnknownOrDisabledUser() {
        var user = users.findById(userId).orElseThrow();
        user.setDisabled(true);
        users.save(user);

        assertThat(handoff.forUser(null)).isEmpty();
        assertThat(handoff.forUser(UUID.randomUUID())).isEmpty();
        assertThat(handoff.forUser(userId)).isEmpty();
    }

    @Test
    void callAsReturnsTheValueAndLeavesTheThreadAsItWas() {
        var before = SecurityContextHolder.getContext().getAuthentication();
        Operator operator = handoff.forUser(userId).orElseThrow();
        SecurityContextHolder.clearContext();

        UUID seen = handoff.callAs(operator, () -> actors.resolve().userId());

        assertThat(seen).isEqualTo(userId);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(before).isNotNull();
    }
}

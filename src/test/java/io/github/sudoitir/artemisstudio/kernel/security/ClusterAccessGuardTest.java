package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceForbiddenException;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard.Requirement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What a refused resource looks like (team-access spec): not found when it cannot be read, forbidden and naming
 * the permission when it can, and every resource of a composite operation checked before anything is done.
 */
class ClusterAccessGuardTest {

    private final UUID cluster = UUID.randomUUID();
    private final PermissionResolver perm = mock(PermissionResolver.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ClusterAccessGuard guard = new ClusterAccessGuard(perm, events);

    private static final ResourceRef ORDERS = ResourceRef.queue("orders.in");
    private static final ResourceRef BILLING = ResourceRef.queue("billing.in");
    private static final ResourceRef BILLING_ADDRESS = ResourceRef.address("billing.in");

    private void canRead(ResourceRef resource) {
        when(perm.can(cluster, resource, resource.readPermission())).thenReturn(true);
    }

    private void canDo(ResourceRef resource, String permission) {
        canRead(resource);
        when(perm.can(cluster, resource, permission)).thenReturn(true);
    }

    @Test
    void aResourceThatMayBeReadAndActedOnPasses() {
        canDo(ORDERS, "queue:purge");

        assertThatCode(() -> guard.requireResource(cluster, ORDERS, "queue:purge"))
                .doesNotThrowAnyException();
    }

    @Test
    void aResourceThatCannotBeReadIsNotFoundLikeAMissingOne() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireResource(cluster, BILLING, "queue:purge"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(new NotFoundException("queue", "billing.in").getMessage());
    }

    @Test
    void anUnreadableResourceOfAClusterTheCallerCannotSeeIsTheClusterNotFound() {
        assertThatThrownBy(() -> guard.requireResource(cluster, BILLING, "queue:purge"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(new NotFoundException("cluster", cluster).getMessage());
    }

    @Test
    void aReadableResourceWithoutThePermissionIsForbiddenNamingBoth() {
        canRead(ORDERS);

        assertThatThrownBy(() -> guard.requireResource(cluster, ORDERS, "queue:purge"))
                .isInstanceOfSatisfying(ResourceForbiddenException.class, e -> {
                    assertThat(e.permission()).isEqualTo("queue:purge");
                    assertThat(e.kind()).isEqualTo("queue");
                    assertThat(e.name()).isEqualTo("orders.in");
                    assertThat(e.getMessage()).contains("queue:purge", "orders.in");
                });
    }

    @Test
    void anAddressIsReadThroughAddressRead() {
        canRead(BILLING_ADDRESS);

        assertThatThrownBy(() -> guard.requireResource(cluster, BILLING_ADDRESS, "message:send"))
                .isInstanceOfSatisfying(
                        ResourceForbiddenException.class,
                        e -> assertThat(e.kind()).isEqualTo("address"));
    }

    @Test
    void aCompositeIsCheckedForReadsBeforeAnyRefusalAndNamesNoneOfTheUnreadable() {
        // The source is readable but not movable; the target cannot be read at all.
        canRead(ORDERS);
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        List<Requirement> requirements =
                List.of(new Requirement(ORDERS, "message:move"), new Requirement(BILLING_ADDRESS, "message:send"));

        assertThatThrownBy(() -> guard.requireAll(cluster, requirements))
                .isInstanceOf(NotFoundException.class)
                .hasMessageNotContaining("billing.in")
                .hasMessageNotContaining("orders.in");
    }

    @Test
    void aCompositeWhoseResourcesAreAllReadableIsRefusedOnTheFirstMissingPermission() {
        canDo(ORDERS, "message:move");
        canRead(BILLING_ADDRESS);

        assertThatThrownBy(() -> guard.requireAll(
                        cluster,
                        List.of(
                                new Requirement(ORDERS, "message:move"),
                                new Requirement(BILLING_ADDRESS, "message:send"))))
                .isInstanceOfSatisfying(
                        ResourceForbiddenException.class,
                        e -> assertThat(e.permission()).isEqualTo("message:send"));
    }

    @Test
    void mayAllIsTheSameQuestionWithoutTheRefusal() {
        canDo(ORDERS, "queue:purge");
        canRead(BILLING);

        assertThat(guard.mayAll(cluster, List.of(new Requirement(ORDERS, "queue:purge"))))
                .isTrue();
        assertThat(guard.mayAll(cluster, List.of(new Requirement(BILLING, "queue:purge"))))
                .isFalse();
    }

    // ---- creating --------------------------------------------------------------------------------

    @Test
    void aNameTheCallerMayCreateIsAllowedWithoutBeingReadableFirst() {
        when(perm.can(cluster, ResourceRef.queue("orders.retry"), "queue:create"))
                .thenReturn(true);

        assertThatCode(() -> guard.requireCreate(cluster, ResourceRef.queue("orders.retry"), "queue:create"))
                .doesNotThrowAnyException();
    }

    @Test
    void aNameOutsideTheCallersPatternsIsRefusedNamingWhereTheyMayCreate() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);
        when(perm.patternsHolding(cluster, ResourceKind.QUEUE, "queue:create")).thenReturn(List.of("orders.#"));

        assertThatThrownBy(() -> guard.requireCreate(cluster, ResourceRef.queue("misc.temp"), "queue:create"))
                .isInstanceOfSatisfying(ResourceForbiddenException.class, e -> {
                    assertThat(e.permission()).isEqualTo("queue:create");
                    assertThat(e.name()).isEqualTo("misc.temp");
                    assertThat(e.getMessage()).contains("queue:create", "misc.temp", "orders.#");
                });
    }

    @Test
    void aCallerWithNoPatternsIsToldNothingAboutWhereTheyMayCreate() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireCreate(cluster, ResourceRef.queue("misc.temp"), "queue:create"))
                .isInstanceOfSatisfying(
                        ResourceForbiddenException.class,
                        e -> assertThat(e.getMessage()).doesNotContain("under"));
    }

    @Test
    void creatingOnAClusterTheCallerCannotSeeIsTheClusterNotFound() {
        ResourceRef misc = ResourceRef.queue("misc.temp");

        assertThatThrownBy(() -> guard.requireCreate(cluster, misc, "queue:create"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(new NotFoundException("cluster", cluster).getMessage());
    }

    // ---- visibility ------------------------------------------------------------------------------

    @Test
    void aClusterTheCallerCannotSeeIsNotFoundToList() {
        assertThatThrownBy(() -> guard.requireVisible(cluster)).isInstanceOf(NotFoundException.class);

        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatCode(() -> guard.requireVisible(cluster)).doesNotThrowAnyException();
    }

    @Test
    void aPatternTheCallerHoldsOnEveryNameOfPasses() {
        when(perm.canOnAll(cluster, ResourceKind.QUEUE, "orders.#", "capture:write"))
                .thenReturn(true);

        assertThatCode(() -> guard.requireOnAll(cluster, ResourceKind.QUEUE, "orders.#", "capture:write"))
                .doesNotThrowAnyException();
    }

    @Test
    void aPatternThatCouldReachOtherNamesIsForbiddenAndAsksForANarrowerOne() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireOnAll(cluster, ResourceKind.QUEUE, "#", "capture:write"))
                .isInstanceOfSatisfying(ResourceForbiddenException.class, e -> {
                    assertThat(e.permission()).isEqualTo("capture:write");
                    assertThat(e.name()).isEqualTo("#");
                    assertThat(e.getMessage()).contains("Narrow it");
                });
    }

    @Test
    void aPatternOnAClusterTheCallerCannotSeeIsTheClusterNotFound() {
        assertThatThrownBy(() -> guard.requireOnAll(cluster, ResourceKind.QUEUE, "#", "capture:write"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(new NotFoundException("cluster", cluster).getMessage());
    }

    // ---- refusals are published for the audit trail ----------------------------------------------------

    @Test
    void aForbiddenResourceIsRefusedAsForbidden() {
        canRead(ORDERS);

        assertThatThrownBy(() -> guard.requireResource(cluster, ORDERS, "queue:purge"))
                .isInstanceOf(ResourceForbiddenException.class);

        verify(events).publishEvent(new AccessRefused(cluster, "queue:purge", ORDERS, false));
    }

    @Test
    void anUnreadableResourceIsRefusedAsHiddenOnTheReadPermission() {
        when(perm.canSeeCluster(cluster)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireResource(cluster, BILLING, "queue:purge"))
                .isInstanceOf(NotFoundException.class);

        verify(events).publishEvent(new AccessRefused(cluster, "queue:read", BILLING, true));
    }

    @Test
    void aClusterTheCallerCannotSeeIsRefusedAsHidden() {
        assertThatThrownBy(() -> guard.requireCluster(cluster, "cluster:write")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> guard.requireVisible(cluster)).isInstanceOf(NotFoundException.class);

        verify(events).publishEvent(new AccessRefused(cluster, "cluster:write", null, true));
        verify(events).publishEvent(new AccessRefused(cluster, "cluster:read", null, true));
    }

    @Test
    void aCheckThatPassesPublishesNothing() {
        canDo(ORDERS, "queue:purge");

        guard.requireResource(cluster, ORDERS, "queue:purge");

        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any(AccessRefused.class));
    }
}

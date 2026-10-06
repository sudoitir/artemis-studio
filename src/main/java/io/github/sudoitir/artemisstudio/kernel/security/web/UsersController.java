package io.github.sudoitir.artemisstudio.kernel.security.web;

import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.internal.EffectiveAccess;
import io.github.sudoitir.artemisstudio.kernel.security.internal.SessionService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.UserService;
import io.github.sudoitir.artemisstudio.kernel.security.web.AccessViews.AccessCheckView;
import io.github.sudoitir.artemisstudio.kernel.security.web.SessionViews.AccountSessionView;
import io.github.sudoitir.artemisstudio.kernel.security.web.SessionViews.EndedSessionsView;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.CreateUserRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.EffectivePermissionView;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.SetDisabledRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User CRUD and role grants (authorization spec). Every write needs {@code user:admin}. */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UsersController {

    private final UserService users;
    private final EffectiveAccess effectivePermissions;
    private final SessionService sessionService;

    @GetMapping("/{userId}/effective-permissions")
    public PagedView<EffectivePermissionView> effectivePermissions(
            @PathVariable UUID userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(effectivePermissions.of(userId), null);
    }

    /**
     * Every catalogue permission for the user, here, and each way they hold it. Name a queue or address, with its
     * cluster, to include the team roles and shares that reach it.
     */
    @GetMapping("/{userId}/access-check")
    public PagedView<AccessCheckView> accessCheck(
            @PathVariable UUID userId,
            @RequestParam(required = false) UUID clusterId,
            @RequestParam(required = false) ResourceKind kind,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size)
                .paginate(effectivePermissions.check(userId, clusterId, kind, name), null);
    }

    @GetMapping("/{userId}/sessions")
    public PagedView<AccountSessionView> sessionsOf(
            @PathVariable UUID userId,
            HttpServletRequest req,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(sessionService.listOf(userId, req), null);
    }

    /** Ends every session of the user, except the caller's own current one. */
    @DeleteMapping("/{userId}/sessions")
    public EndedSessionsView endSessionsOf(@PathVariable UUID userId, HttpServletRequest req) {
        return sessionService.endAllOf(userId, req);
    }

    @DeleteMapping("/{userId}/sessions/{handle}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endSessionOf(
            @PathVariable UUID userId, @PathVariable String handle, HttpServletRequest req, HttpServletResponse resp) {
        sessionService.endOf(userId, handle, req, resp);
    }

    @GetMapping
    public PagedView<UserView> list(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(users.list(), null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest request) {
        return users.create(request);
    }

    @PutMapping("/{userId}/disabled")
    public UserView setDisabled(@PathVariable UUID userId, @RequestBody SetDisabledRequest request) {
        return users.setDisabled(userId, request.disabled());
    }

    @PutMapping("/{userId}/unlock")
    public UserView unlock(@PathVariable UUID userId) {
        return users.unlock(userId);
    }

    /**
     * Remove the user's second factors, recovery codes, trusted devices and API tokens, and end their
     * sessions. Needs {@code user:admin} and a recent step-up; refused for oneself ({@code 409 self-reset})
     * and, when the user must hold a factor, unless this session verified one ({@code 403 mfa-required}).
     */
    @DeleteMapping("/{userId}/second-factors")
    public UserView resetSecondFactors(@PathVariable UUID userId, HttpServletRequest req) {
        return users.resetSecondFactors(userId, req);
    }

    @PostMapping("/{userId}/grants")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addGrant(@PathVariable UUID userId, @Valid @RequestBody GrantRequest request) {
        users.addGrant(userId, request);
    }

    @DeleteMapping("/{userId}/grants/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeGrant(
            @PathVariable UUID userId,
            @PathVariable UUID roleId,
            @RequestParam String scopeType,
            @RequestParam(required = false) UUID scopeId) {
        users.removeGrant(userId, roleId, scopeType, scopeId);
    }
}

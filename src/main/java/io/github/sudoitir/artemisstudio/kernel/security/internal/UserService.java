package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.AccountLockout;
import io.github.sudoitir.artemisstudio.kernel.security.AdministrationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.PasswordRules;
import io.github.sudoitir.artemisstudio.kernel.security.PersonalTokens;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactorRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactors;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.CreateUserRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.GrantSummary;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.UserView;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration: create/disable local accounts and grant/revoke role
 * assignments (authorization spec). The last-global-administrator guards are
 * the load-bearing safety net for the fully-dynamic permission model
 * (design.md decision 4) — enforced here, in the same transaction as the
 * mutation, never left to the UI alone (ADR-0022 precedent).
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String GLOBAL_SCOPE = "GLOBAL";

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final PasswordEncoder passwordEncoder;
    private final AdministrationAudit audit;
    private final AccountLockout lockout;
    private final SessionTerminator sessions;
    private final Optional<PasswordRules> passwordRules;
    private final Optional<SecondFactors> secondFactors;
    private final Optional<PersonalTokens> personalTokens;
    private final SessionAuthentication sessionState;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public List<UserView> list() {
        return users.findAllByOrderByUsername().stream().map(this::toView).toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public UserView create(CreateUserRequest request) {
        if (users.existsByUsernameIgnoreCase(request.username())) {
            throw new ConflictException(
                    "duplicate-username", "A user named '" + request.username() + "' already exists.");
        }
        passwordRules.ifPresent(rules -> rules.check(request.username(), request.password()));
        AppUserEntity user =
                AppUserEntity.local(request.username(), request.email(), passwordEncoder.encode(request.password()));
        user.setMustChangePassword(true);
        users.save(user);
        audit.changed("USER_CREATE", "user", user.getUsername(), null);
        return toView(user);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public UserView setDisabled(UUID userId, boolean disabled) {
        AppUserEntity user = requireUser(userId);
        if (disabled) {
            guardNotLastAdmin(user, "disable");
        }
        user.setDisabled(disabled);
        users.save(user);
        audit.changed(disabled ? "USER_DISABLE" : "USER_ENABLE", "user", user.getUsername(), null);
        if (disabled) {
            sessions.endSessionsOf(List.of(user.getUsername()));
            secondFactors.ifPresent(f -> f.revokeTrustedDevices(userId, "account disabled"));
        }
        return toView(user);
    }

    /** Lift the account lock and the sign-in throttle for the user, so they can try again at once. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public UserView unlock(UUID userId) {
        AppUserEntity user = requireUser(userId);
        lockout.unlock(user.getId(), user.getUsername());
        audit.changed("ACCOUNT_UNLOCK", "user", user.getUsername(), null);
        // The entity still holds the lock it was loaded with; the columns are written by SQL, not through it.
        return toView(user, null);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public void addGrant(UUID userId, GrantRequest request) {
        AppUserEntity user = requireUser(userId);
        RoleEntity role =
                roles.findById(request.roleId()).orElseThrow(() -> new NotFoundException("role", request.roleId()));
        UUID scopeId = request.scopeId() != null ? request.scopeId() : ScopeIds.GLOBAL;
        userRoles.save(new UserRoleEntity(userId, role.getId(), request.scopeType(), scopeId));
        audit.changed(
                "GRANT_ADD",
                "user",
                user.getUsername(),
                Map.of("role", role.getName(), "scopeType", request.scopeType()));
        if (role.isRequiresMfa()) {
            // Sessions signed in before the grant never verified a second factor; the next sign-in enforces it.
            sessions.endSessionsOf(List.of(user.getUsername()));
        }
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public void removeGrant(UUID userId, UUID roleId, String scopeType, UUID scopeId) {
        AppUserEntity user = requireUser(userId);
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        UUID resolvedScopeId = scopeId != null ? scopeId : ScopeIds.GLOBAL;

        boolean isGlobalAdminGrant = GLOBAL_SCOPE.equals(scopeType)
                && role.getName().equals("ADMIN")
                && ScopeIds.GLOBAL.equals(resolvedScopeId);
        if (isGlobalAdminGrant) {
            guardNotLastAdmin(user, "strip the administrator role from");
            StudioPrincipal principal = currentPrincipalOrNull();
            if (principal != null && principal.userId().equals(userId)) {
                throw new ConflictException("self-revoke-admin", "You cannot remove your own administrator grant.");
            }
        }

        userRoles.deleteById(new UserRoleEntity(userId, roleId, scopeType, resolvedScopeId).getId());
        audit.changed(
                "GRANT_REMOVE", "user", user.getUsername(), Map.of("role", role.getName(), "scopeType", scopeType));
        sessions.endSessionsOf(List.of(user.getUsername()));
    }

    /**
     * Remove a user's second factors, as an administrator does when they lost their device: the
     * authenticator app, passkeys, recovery codes and trusted devices go, their API tokens are revoked
     * and their sessions end, so at the next sign-in they enrol again if their role requires a factor.
     * It needs a step-up; it is never for one's own account, where recovery codes are the way; and when the
     * target must hold a factor, the administrator's own session must have verified one.
     */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public UserView resetSecondFactors(UUID userId, HttpServletRequest request) {
        if (!sessionState.recentlyAuthenticated(request)) {
            throw new ReauthenticationRequiredException();
        }
        AppUserEntity user = requireUser(userId);
        StudioPrincipal actor = currentPrincipalOrNull();
        if (actor != null && userId.equals(actor.userId())) {
            throw new ConflictException("self-reset", "Use one of your recovery codes, or ask another administrator.");
        }
        boolean targetRequired = secondFactors.map(f -> f.required(userId)).orElse(false);
        if (targetRequired
                && sessionState.facts(request).map(SessionFacts::mfaVerifiedAt).isEmpty()) {
            throw new SecondFactorRequiredException(
                    "This user must hold a second factor, so you must have verified yours in this session first."
                            + " Sign in again and give your authenticator code or passkey.");
        }
        secondFactors.ifPresent(f -> f.reset(userId));
        int tokens = personalTokens.map(t -> t.revokeAllOf(userId)).orElse(0);
        sessions.endSessionsOf(List.of(user.getUsername()));
        audit.changed("MFA_RESET", "user", user.getUsername(), Map.of("tokensRevoked", tokens));
        return toView(user);
    }

    private void guardNotLastAdmin(AppUserEntity user, String verb) {
        RoleEntity admin = roles.findByName("ADMIN").orElseThrow(() -> new IllegalStateException("ADMIN role missing"));
        long adminHolders = userRoles.findByIdRoleId(admin.getId()).stream()
                .filter(ur -> GLOBAL_SCOPE.equals(ur.getScopeType()))
                .map(UserRoleEntity::getUserId)
                .distinct()
                .filter(id -> users.findById(id).map(u -> !u.isDisabled()).orElse(false))
                .count();
        boolean userHoldsAdmin = userRoles.findByIdUserId(user.getId()).stream()
                .anyMatch(ur -> ur.getRoleId().equals(admin.getId()) && GLOBAL_SCOPE.equals(ur.getScopeType()));
        if (userHoldsAdmin && adminHolders <= 1) {
            throw new ConflictException("last-admin", "Cannot " + verb + " the last enabled global administrator.");
        }
    }

    private static StudioPrincipal currentPrincipalOrNull() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return auth != null && auth.getPrincipal() instanceof StudioPrincipal p ? p : null;
    }

    private AppUserEntity requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("user", userId));
    }

    private UserView toView(AppUserEntity user) {
        Instant lock = user.getLockedUntil();
        return toView(user, lock != null && lock.isAfter(Instant.now()) ? lock : null);
    }

    private UserView toView(AppUserEntity user, Instant lockedUntil) {
        List<GrantSummary> grants = userRoles.findByIdUserId(user.getId()).stream()
                .map(ur -> new GrantSummary(
                        roles.findById(ur.getRoleId()).map(RoleEntity::getName).orElse("?"),
                        ur.getRoleId(),
                        ur.getScopeType(),
                        ScopeIds.GLOBAL.equals(ur.getScopeId()) ? null : ur.getScopeId()))
                .toList();
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getProviderId(),
                user.isDisabled(),
                user.isMustChangePassword(),
                lockedUntil,
                secondFactors.map(f -> f.enrolledMethods(user.getId())).orElse(List.of()),
                secondFactors.map(f -> f.required(user.getId())).orElse(false),
                grants);
    }
}

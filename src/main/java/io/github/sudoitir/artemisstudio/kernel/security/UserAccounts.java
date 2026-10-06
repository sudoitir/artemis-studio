package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user store as identity provider modules see it (ADR-0073): enough to check a password,
 * change one and create the first administrator, without handing out the kernel's persistence.
 */
@Component
@RequiredArgsConstructor
public class UserAccounts {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final TeamMemberRepository teamMembers;
    private final PluginInstallers installers;

    /** A user as sign-in needs it. {@code passwordHash} is {@code null} for a user an external provider created. */
    public record Account(
            UUID id,
            String username,
            String passwordHash,
            boolean disabled,
            boolean mustChangePassword,
            String providerId,
            String externalSubject) {}

    @Transactional(readOnly = true)
    public Optional<Account> byUsername(String username) {
        return users.findByUsername(username).map(UserAccounts::account);
    }

    /**
     * Serialise the caller's transaction with every other that locks this account, until it ends: work
     * that reads the account's state and then acts on it, such as issuing recovery codes to whoever
     * enrols the first factor, so two of them cannot both find the account bare.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID userId) {
        users.findWithLockById(userId).orElseThrow(() -> new NotFoundException("user", userId));
    }

    @Transactional(readOnly = true)
    public Optional<Account> byId(UUID userId) {
        return users.findById(userId).map(UserAccounts::account);
    }

    /**
     * Whether the user holds a role that requires a second factor (ADR-0143): at any scope, or as a team
     * member, directly or through a directory group.
     */
    @Transactional(readOnly = true)
    public boolean holdsMfaRole(UUID userId) {
        return Stream.concat(
                        userRoles.findByIdUserId(userId).stream().map(UserRoleEntity::getRoleId),
                        teamMembers.findHeldBy(userId).stream().map(TeamMemberEntity::getRoleId))
                .anyMatch(roleId ->
                        roles.findById(roleId).map(RoleEntity::isRequiresMfa).orElse(false));
    }

    @Transactional(readOnly = true)
    public boolean anyExist() {
        return users.count() > 0;
    }

    /** Replace the user's password hash and lift the must-change-password gate. */
    @Transactional
    public void changePassword(UUID userId, String passwordHash) {
        AppUserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("user", userId));
        user.setPasswordHash(passwordHash);
        user.setMustChangePassword(false);
        users.save(user);
    }

    /** The user must change their password at their next sign-in. */
    @Transactional
    public void requirePasswordChange(UUID userId) {
        AppUserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("user", userId));
        user.setMustChangePassword(true);
        users.save(user);
    }

    /**
     * Create a global administrator who must change the password at first sign-in, but only while
     * no account exists at all.
     *
     * @return whether the account was created
     */
    @Transactional
    public boolean createFirstAdministrator(String username, String passwordHash) {
        if (users.count() > 0) {
            return false;
        }
        AppUserEntity admin = AppUserEntity.local(username, null, passwordHash);
        admin.setMustChangePassword(true);
        // Flushed now: plugin_installer below references the row through a plain SQL foreign key.
        admin = users.saveAndFlush(admin);
        RoleEntity role = roles.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException("the built-in ADMIN role is missing"));
        userRoles.save(new UserRoleEntity(admin.getId(), role.getId(), "GLOBAL", ScopeIds.GLOBAL));
        // The person who set Studio up can install plugins (ADR-0103); nobody else can until they say so.
        installers.grant(admin.getId(), "bootstrap");
        return true;
    }

    private static Account account(AppUserEntity user) {
        return new Account(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.isDisabled(),
                user.isMustChangePassword(),
                user.getProviderId(),
                user.getExternalSubject());
    }
}

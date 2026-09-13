package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
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

    /** A user as sign-in needs it. {@code passwordHash} is {@code null} for a user an external provider created. */
    public record Account(
            UUID id, String username, String passwordHash, boolean disabled, boolean mustChangePassword) {}

    @Transactional(readOnly = true)
    public Optional<Account> byUsername(String username) {
        return users.findByUsername(username).map(UserAccounts::account);
    }

    @Transactional(readOnly = true)
    public Optional<Account> byId(UUID userId) {
        return users.findById(userId).map(UserAccounts::account);
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
        admin = users.save(admin);
        RoleEntity role = roles.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException("the built-in ADMIN role is missing"));
        userRoles.save(new UserRoleEntity(admin.getId(), role.getId(), "GLOBAL", ScopeIds.GLOBAL));
        return true;
    }

    private static Account account(AppUserEntity user) {
        return new Account(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.isDisabled(),
                user.isMustChangePassword());
    }
}

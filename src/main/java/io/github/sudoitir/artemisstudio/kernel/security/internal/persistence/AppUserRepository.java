package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    Optional<AppUserEntity> findByUsername(String username);

    /** Whether the name is taken in any case: usernames are unique ignoring case. */
    boolean existsByUsernameIgnoreCase(String username);

    /** The user, its row locked until the transaction ends, so what must not overlap for one account does not. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppUserEntity> findWithLockById(UUID id);

    Optional<AppUserEntity> findByProviderIdAndExternalSubject(String providerId, String externalSubject);

    List<AppUserEntity> findAllByOrderByUsername();

    /** Enabled accounts whose name starts with the prefix, ignoring case, for picking a member. */
    List<AppUserEntity> findTop20ByDisabledFalseAndUsernameStartingWithIgnoreCaseOrderByUsername(String prefix);

    /** A provider's accounts that can still sign in, as revalidation needs them (ADR-0156). */
    interface EnabledAccount {
        UUID getId();

        String getUsername();

        String getExternalSubject();
    }

    List<EnabledAccount> findByProviderIdAndDisabledFalse(String providerId);
}

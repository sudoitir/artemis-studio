package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What identifies an account as one person, for telling two accounts apart: when it was created, its
 * email and the external identities it is linked to. Read as it stands now.
 */
@Component
@RequiredArgsConstructor
public class UserIdentityFacts {

    private final AppUserRepository users;

    /** One external identity: the provider and the subject it knows the person by. */
    public record Identity(String providerId, String externalSubject) {}

    /**
     * @param email as stored, null when the account has none; compare it ignoring case
     * @param identities empty for a local account
     */
    public record Facts(UUID userId, Instant createdAt, String email, Set<Identity> identities, boolean enabled) {}

    @Transactional(readOnly = true)
    public Optional<Facts> of(UUID userId) {
        return users.findById(userId)
                .map(user -> new Facts(
                        user.getId(),
                        user.getCreatedAt(),
                        user.getEmail(),
                        user.getExternalSubject() == null
                                ? Set.of()
                                : Set.of(new Identity(user.getProviderId(), user.getExternalSubject())),
                        !user.isDisabled()));
    }
}

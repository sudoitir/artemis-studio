package io.github.sudoitir.artemisstudio.kernel.security.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Maps {@code identity_provider_default_role}: the role a provider's user gets when their groups
 * match no mapping. No row means such a user is refused (ADR-0073).
 */
@Entity
@Table(name = "identity_provider_default_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DefaultRoleEntity {

    @Id
    @Column(name = "provider_id", nullable = false, updatable = false)
    private String providerId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    public DefaultRoleEntity(String providerId, UUID roleId) {
        this.providerId = providerId;
        this.roleId = roleId;
    }
}

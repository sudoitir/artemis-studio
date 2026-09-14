package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Maps {@code identity_group_mapping}: a provider's group -> a role grant, re-applied every sign-in (ADR-0073). */
@Entity
@Table(name = "identity_group_mapping")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GroupMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private String providerId;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    public GroupMappingEntity(String providerId, String groupName, UUID roleId, String scopeType, UUID scopeId) {
        this.providerId = providerId;
        this.groupName = groupName;
        this.roleId = roleId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}

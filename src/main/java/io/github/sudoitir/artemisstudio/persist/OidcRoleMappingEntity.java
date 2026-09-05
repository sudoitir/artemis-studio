package io.github.sudoitir.artemisstudio.persist;

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
import lombok.Setter;

/** Maps {@code oidc_role_mapping} (changeset 014): claim value -> role grant, re-applied every login (ADR-0040). */
@Entity
@Table(name = "oidc_role_mapping")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OidcRoleMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "claim", nullable = false)
    private String claim;

    @Column(name = "claim_value", nullable = false)
    private String claimValue;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "scope_type", nullable = false)
    private String scopeType = "GLOBAL";

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    public OidcRoleMappingEntity(String claim, String claimValue, UUID roleId, String scopeType, UUID scopeId) {
        this.claim = claim;
        this.claimValue = claimValue;
        this.roleId = roleId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}

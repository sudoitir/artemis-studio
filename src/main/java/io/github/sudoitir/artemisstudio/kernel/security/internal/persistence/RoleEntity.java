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
import lombok.Setter;

/**
 * Maps {@code role} (changesets 003, 014, 0004). {@code builtin} roles (ADMIN,
 * OPERATOR, VIEWER, TEAM_VIEWER, TEAM_OPERATOR, TEAM_ADMIN) are seeded and immutable except for {@code requiresMfa} (ADR-0038, ADR-0143);
 * a custom role has any permission combination and can be freely edited.
 */
@Entity
@Table(name = "role")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "builtin", nullable = false)
    private boolean builtin;

    /** Local accounts holding this role need a second factor (ADR-0143); the built-in ADMIN role does by default. */
    @Column(name = "requires_mfa", nullable = false)
    private boolean requiresMfa;

    /** The role may be a team role: it holds only permissions that act on a resource, plus {@code team:admin}. */
    @Column(name = "team_assignable", nullable = false)
    private boolean teamAssignable;

    public RoleEntity(String name, boolean builtin) {
        this.name = name;
        this.builtin = builtin;
    }
}

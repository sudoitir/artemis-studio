package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Maps {@code user_role} (changeset 003): a role grant to a user at a scope. The
 * nil UUID (see {@link ScopeIds#GLOBAL}) stands in for "no specific scope id"
 * when {@code scopeType} is {@code GLOBAL}, so scope id stays a non-null PK column.
 */
@Entity
@Table(name = "user_role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRoleEntity {

    @EmbeddedId
    private Key id;

    public UserRoleEntity(UUID userId, UUID roleId, String scopeType, UUID scopeId) {
        this.id = new Key(userId, roleId, scopeType, scopeId);
    }

    public UUID getUserId() {
        return id.userId;
    }

    public UUID getRoleId() {
        return id.roleId;
    }

    public String getScopeType() {
        return id.scopeType;
    }

    public UUID getScopeId() {
        return id.scopeId;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "user_id")
        private UUID userId;

        @Column(name = "role_id")
        private UUID roleId;

        @Column(name = "scope_type")
        private String scopeType;

        @Column(name = "scope_id")
        private UUID scopeId;

        Key(UUID userId, UUID roleId, String scopeType, UUID scopeId) {
            this.userId = userId;
            this.roleId = roleId;
            this.scopeType = scopeType;
            this.scopeId = scopeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(userId, key.userId)
                    && Objects.equals(roleId, key.roleId)
                    && Objects.equals(scopeType, key.scopeType)
                    && Objects.equals(scopeId, key.scopeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId, scopeType, scopeId);
        }
    }
}

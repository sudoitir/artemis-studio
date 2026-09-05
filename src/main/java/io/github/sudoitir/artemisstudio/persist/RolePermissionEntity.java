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

/** Maps {@code role_permission} (changeset 003): one dynamic permission string per row. */
@Entity
@Table(name = "role_permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RolePermissionEntity {

    @EmbeddedId
    private Key id;

    public RolePermissionEntity(UUID roleId, String action) {
        this.id = new Key(roleId, action);
    }

    public UUID getRoleId() {
        return id.roleId;
    }

    public String getAction() {
        return id.action;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "role_id")
        private UUID roleId;

        @Column(name = "action")
        private String action;

        Key(UUID roleId, String action) {
            this.roleId = roleId;
            this.action = action;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(roleId, key.roleId) && Objects.equals(action, key.action);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, action);
        }
    }
}

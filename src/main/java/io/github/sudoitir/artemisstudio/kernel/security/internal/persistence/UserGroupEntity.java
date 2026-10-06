package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

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
 * Maps {@code user_group} (changeset kernel-security 0012): a directory group of an identity provider that
 * an external user was in at their last sign-in.
 */
@Entity
@Table(name = "user_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGroupEntity {

    @EmbeddedId
    private Key id;

    public UserGroupEntity(UUID userId, String providerId, String groupName) {
        this.id = new Key(userId, providerId, groupName);
    }

    public String getProviderId() {
        return id.providerId;
    }

    public String getGroupName() {
        return id.groupName;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "user_id")
        private UUID userId;

        @Column(name = "provider_id")
        private String providerId;

        @Column(name = "group_name")
        private String groupName;

        Key(UUID userId, String providerId, String groupName) {
            this.userId = userId;
            this.providerId = providerId;
            this.groupName = groupName;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key key
                    && Objects.equals(userId, key.userId)
                    && Objects.equals(providerId, key.providerId)
                    && Objects.equals(groupName, key.groupName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, providerId, groupName);
        }
    }
}

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
 * Maps {@code team_member} (changeset kernel-security 0010): a user ({@code principalType} USER, with
 * {@code userId}) or a directory group ({@code GROUP}, with {@code providerId} and {@code groupName})
 * holding one team role in a team.
 */
@Entity
@Table(name = "team_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TeamMemberEntity {

    public static final String USER = "USER";
    public static final String GROUP = "GROUP";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "principal_type", nullable = false, updatable = false)
    private String principalType;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "provider_id", updatable = false)
    private String providerId;

    @Column(name = "group_name", updatable = false)
    private String groupName;

    @Column(name = "role_id", nullable = false)
    @Setter
    private UUID roleId;

    private TeamMemberEntity(
            UUID teamId, String principalType, UUID userId, String providerId, String groupName, UUID roleId) {
        this.teamId = teamId;
        this.principalType = principalType;
        this.userId = userId;
        this.providerId = providerId;
        this.groupName = groupName;
        this.roleId = roleId;
    }

    public static TeamMemberEntity ofUser(UUID teamId, UUID userId, UUID roleId) {
        return new TeamMemberEntity(teamId, USER, userId, null, null, roleId);
    }

    public static TeamMemberEntity ofGroup(UUID teamId, String providerId, String groupName, UUID roleId) {
        return new TeamMemberEntity(teamId, GROUP, null, providerId, groupName, roleId);
    }
}

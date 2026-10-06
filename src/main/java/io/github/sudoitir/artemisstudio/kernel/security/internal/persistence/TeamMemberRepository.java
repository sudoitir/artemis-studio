package io.github.sudoitir.artemisstudio.kernel.security.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, UUID> {

    List<TeamMemberEntity> findByTeamId(UUID teamId);

    Optional<TeamMemberEntity> findByIdAndTeamId(UUID id, UUID teamId);

    long countByTeamId(UUID teamId);

    boolean existsByRoleId(UUID roleId);

    /** Every membership a user holds, directly or through a directory group they were in at sign-in. */
    @Query("""
            select m from TeamMemberEntity m
            where m.userId = :userId
               or exists (select 1 from UserGroupEntity g
                          where g.id.userId = :userId
                            and g.id.providerId = m.providerId
                            and g.id.groupName = m.groupName)
            """)
    List<TeamMemberEntity> findHeldBy(@Param("userId") UUID userId);

    /** Whether the user is a member of any team, directly or through a group. */
    default boolean isMemberOfAny(UUID userId) {
        return !findHeldBy(userId).isEmpty();
    }
}

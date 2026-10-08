package io.github.sudoitir.artemisstudio.kernel.security;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** What {@link AccessChanges} recorded: who changed whose access, for the approval rules (ADR-0181). */
@Component
@RequiredArgsConstructor
public class AccessChangeLog {

    private final JdbcClient jdbc;

    /**
     * Whether the actor changed anyone else's access since {@code since}: a change to one other user, or
     * one that reaches everyone (a role, a team, a mapping). A change to the actor's own access does not count.
     */
    public boolean changedOthersSince(UUID actorId, Instant since) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM access_change_log
                            WHERE actor_id = ? AND at > ? AND (subject_id IS NULL OR subject_id <> ?))
                        """)
                .params(actorId, java.sql.Timestamp.from(since), actorId)
                .query(Boolean.class)
                .single();
    }
}

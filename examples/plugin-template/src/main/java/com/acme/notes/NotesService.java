package com.acme.notes;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsReader;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notes, stored in the plugin's own schema. Every change is audited the way Studio's own are —
 * the audit row committed before the change, then its outcome — and announced on the live stream
 * so every open Notes view refreshes.
 */
@Service
public class NotesService {

    static final String TOPIC = "acme-notes";

    @PersistenceContext
    private EntityManager em;

    private final AuditService audit;
    private final ActorResolver actors;
    private final SettingsReader settings;
    private final SseHub stream;
    private final Clock clock;

    public NotesService(
            AuditService audit, ActorResolver actors, SettingsReader settings, SseHub stream, Clock clock) {
        this.audit = audit;
        this.actors = actors;
        this.settings = settings;
        this.stream = stream;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Note> list(UUID clusterId, String queue) {
        return em.createQuery(
                        "SELECT n FROM Note n WHERE n.clusterId = :cluster AND n.queue = :queue ORDER BY n.createdAt DESC",
                        Note.class)
                .setParameter("cluster", clusterId)
                .setParameter("queue", queue)
                .setMaxResults(500)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public List<Note> recent(UUID clusterId) {
        return em.createQuery(
                        "SELECT n FROM Note n WHERE n.clusterId = :cluster ORDER BY n.createdAt DESC", Note.class)
                .setParameter("cluster", clusterId)
                .setMaxResults(100)
                .getResultList();
    }

    @Transactional
    public Note add(UUID clusterId, String queue, String body) {
        int max = settings.intValue("acme-notes.max-length");
        if (body.isBlank() || body.length() > max) {
            throw new IllegalArgumentException("A note is 1 to " + max + " characters.");
        }
        var actor = actors.resolve();
        AuditEvent event = audit.begin(actor, "ACME_NOTES_ADD", "queue", queue, clusterId, null, Map.of(), false);
        Note note = new Note(clusterId, queue, actor.username(), body, clock.instant());
        em.persist(note);
        audit.succeed(event, 1);
        stream.publish(clusterId, TOPIC);
        return note;
    }

    @Transactional
    public void delete(UUID clusterId, UUID noteId) {
        Note note = em.find(Note.class, noteId);
        if (note == null || !note.getClusterId().equals(clusterId)) {
            return;
        }
        AuditEvent event = audit.begin(
                actors.resolve(), "ACME_NOTES_DELETE", "queue", note.getQueue(), clusterId, null, Map.of(), false);
        em.remove(note);
        audit.succeed(event, 1);
        stream.publish(clusterId, TOPIC);
    }

    @Transactional
    public void pruneOlderThanAYear() {
        em.createQuery("DELETE FROM Note n WHERE n.createdAt < :cutoff")
                .setParameter("cutoff", clock.instant().minus(Duration.ofDays(365)))
                .executeUpdate();
    }
}

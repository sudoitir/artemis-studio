package com.acme.notes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The plugin's API. Studio forwards {@code /api/v1/clusters/{clusterId}/p/acme-notes/**} (and
 * {@code /api/v1/p/acme-notes/**} for anything outside a cluster) here, after its own sign-in
 * checks. {@code @perm} is Studio's permission resolver, so a role grants {@code acme-notes:*}
 * like any built-in permission, per cluster or everywhere.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/p/acme-notes")
public class NotesController {

    private final NotesService notes;

    public NotesController(NotesService notes) {
        this.notes = notes;
    }

    public record NoteView(UUID id, String queue, String author, String body, Instant createdAt) {
        static NoteView of(Note n) {
            return new NoteView(n.getId(), n.getQueue(), n.getAuthor(), n.getBody(), n.getCreatedAt());
        }
    }

    public record NewNote(String body) {}

    @GetMapping("/notes")
    @PreAuthorize("@perm.can(#clusterId, 'acme-notes:read')")
    public List<NoteView> recent(@PathVariable UUID clusterId) {
        return notes.recent(clusterId).stream().map(NoteView::of).toList();
    }

    @GetMapping("/queues/{queue}/notes")
    @PreAuthorize("@perm.can(#clusterId, 'acme-notes:read')")
    public List<NoteView> list(@PathVariable UUID clusterId, @PathVariable String queue) {
        return notes.list(clusterId, queue).stream().map(NoteView::of).toList();
    }

    @PostMapping("/queues/{queue}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.can(#clusterId, 'acme-notes:write')")
    public NoteView add(@PathVariable UUID clusterId, @PathVariable String queue, @RequestBody NewNote note) {
        return NoteView.of(notes.add(clusterId, queue, note.body() == null ? "" : note.body()));
    }

    /** A rejected note answers 400 with the reason, as a problem detail like Studio's own. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.can(#clusterId, 'acme-notes:write')")
    public void delete(@PathVariable UUID clusterId, @PathVariable UUID noteId) {
        notes.delete(clusterId, noteId);
    }
}

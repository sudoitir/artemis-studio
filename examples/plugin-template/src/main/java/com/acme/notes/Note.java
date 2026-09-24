package com.acme.notes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A note on a queue: {@code note} in the plugin's own schema (see db/changelog). */
@Entity
@Table(name = "note")
public class Note {

    @Id
    private UUID id;

    @Column(name = "cluster_id", nullable = false)
    private UUID clusterId;

    @Column(nullable = false)
    private String queue;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Note() {}

    Note(UUID clusterId, String queue, String author, String body, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.clusterId = clusterId;
        this.queue = queue;
        this.author = author;
        this.body = body;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClusterId() {
        return clusterId;
    }

    public String getQueue() {
        return queue;
    }

    public String getAuthor() {
        return author;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

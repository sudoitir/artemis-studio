package com.acme.notes;

import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * An assistant tool, offered on Studio's MCP endpoint while the plugin runs. Its name starts with
 * the plugin's id in snake_case and is declared in plugin.json with its posture ({@code read} or
 * {@code write}). It checks the caller's permission itself: a tool is called outside any HTTP
 * route Studio could guard for it.
 */
@Component
public class NotesTools {

    private final NotesService notes;
    private final ClusterAccessGuard access;

    public NotesTools(NotesService notes, ClusterAccessGuard access) {
        this.notes = notes;
        this.access = access;
    }

    @McpTool(name = "acme_notes_list", description = "Lists the notes operators left on a queue, newest first.")
    public List<NotesController.NoteView> list(
            @McpToolParam(required = true, description = "The cluster's id") String clusterId,
            @McpToolParam(required = true, description = "The queue's name") String queue) {
        UUID cluster = UUID.fromString(clusterId);
        access.requireCluster(cluster, "acme-notes:read");
        return notes.list(cluster, queue).stream().map(NotesController.NoteView::of).toList();
    }
}

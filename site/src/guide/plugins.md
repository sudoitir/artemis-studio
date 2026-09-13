---
title: Build a plugin
description: Add a feature to Artemis Studio — screens, API, tables and assistant tools — as one module, inside the repository or kept in its own.
---

# Build a plugin

A plugin is a **feature module**: one backend package, one frontend folder and its
own tables, all under one id. It is compiled into Studio, so the build checks its
boundaries, its schema and its types together with everything else
([ADR-0069](/reference/adr/0069-kernel-plugin-modular-monolith)). Once built in, it can
be turned off at startup like any other feature.

This page builds `notes`: operator notes per cluster, with a screen, an API, a
table and an assistant tool. Every piece is optional except the first three files.

## 1. Backend module

```
src/main/java/io/github/sudoitir/artemisstudio/feature/notes/
  package-info.java        what the module may depend on
  NotesModule.java         its descriptor
  NotesFeature.java        its configuration, loaded only while enabled
  NoteService.java
  web/NotesController.java
  mcp/NotesMcpTools.java
  internal/persistence/NoteEntity.java, NoteRepository.java
```

Keep the package under `io.github.sudoitir.artemisstudio.feature`: that is what the
module and boundary tests scan.

```java
// package-info.java
@ApplicationModule(
        displayName = "Notes",
        allowedDependencies = {"kernel.audit", "kernel.plugin", "kernel.security", "platform.clusters", "platform.mcp"})
package io.github.sudoitir.artemisstudio.feature.notes;

import org.springframework.modulith.ApplicationModule;
```

```java
// NotesModule.java — what Studio must know even while the feature is off
public final class NotesModule {

    public static final String NOTE_READ = "note:read";
    public static final String NOTE_WRITE = "note:write";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("notes")
            .title("Notes")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(new PermissionDef(NOTE_READ, "Read cluster notes"))
            .permission(new PermissionDef(NOTE_WRITE, "Write cluster notes"))
            .apiPrefix("/api/v1/clusters/{clusterId}/notes")
            .mcpTool(new McpToolDef("list_notes", McpToolDef.Posture.READ, "A cluster's operator notes.", List.of()))
            .build();

    private NotesModule() {}
}
```

```java
// NotesFeature.java — scans this package while artemis-studio.features.notes.enabled is not false
@FeatureModule("notes")
public class NotesFeature {}
```

The service checks the permission and audits the write; the controller is ordinary
Spring MVC.

```java
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteRepository notes;
    private final ClusterAccessGuard clusterAccess;
    private final ActorResolver actors;
    private final AuditService audit;

    public List<NoteView> list(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, NotesModule.NOTE_READ);
        return notes.findByClusterIdOrderByCreatedAtDesc(clusterId).stream().map(NoteView::of).toList();
    }

    @Transactional
    public NoteView add(UUID clusterId, String text) {
        clusterAccess.requireCluster(clusterId, NotesModule.NOTE_WRITE);
        AuditEvent event = audit.begin(actors.resolve(), "ADD_NOTE", "CLUSTER", null, clusterId, null, Map.of(), false);
        NoteView note = NoteView.of(notes.save(new NoteEntity(clusterId, text)));
        audit.succeed(event, 1);
        return note;
    }
}
```

```java
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/notes")
@RequiredArgsConstructor
public class NotesController {

    private final NoteService notes;

    @GetMapping
    public List<NoteView> list(@PathVariable UUID clusterId) {
        return notes.list(clusterId);
    }

    @PostMapping
    public NoteView add(@PathVariable UUID clusterId, @RequestBody @Valid NoteRequest request) {
        return notes.add(clusterId, request.text());
    }
}
```

Entities and repositories live in `internal.persistence`; nothing outside the module
may use them.

## 2. Database change

The module owns its tables in its own changelog.

```
src/main/resources/db/changelog/feature/notes/
  changelog.xml
  changes/0001-notes.sql
```

```xml
<!-- changelog.xml -->
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <includeAll path="changes/" relativeToChangelogFile="true"/>
</databaseChangeLog>
```

```sql
--liquibase formatted sql

--changeset artemis-studio:feature-notes-0001
CREATE TABLE note (
    created_at timestamptz NOT NULL DEFAULT now(),
    text       text        NOT NULL,
    id         uuid        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    cluster_id uuid        NOT NULL REFERENCES cluster(id) ON DELETE CASCADE
);
CREATE INDEX ix_note_cluster ON note (cluster_id, created_at DESC);
--rollback DROP TABLE note;
```

Columns go widest-aligned first (timestamps, then text, then uuid and boolean). A
foreign key onto another module's table needs that module in `allowedDependencies`
(`cluster` belongs to `platform.clusters`, which is why it is listed above), and
`ON DELETE CASCADE` removes the notes with their cluster. Never edit a released changeset: add
`0002-….sql` beside it.

Include it from `db.changelog-master.xml`, after the modules it references:

```xml
<include file="db/changelog/feature/notes/changelog.xml" relativeToChangelogFile="false"/>
```

## 3. MCP tool

A tool is a Spring AI `@McpTool` in the module's `mcp` package. It is registered only
while the feature is enabled, and the catalogue entry in the descriptor is what
`studio_help` shows.

```java
@Component
@RequiredArgsConstructor
public class NotesMcpTools {

    private final NoteService notes;

    @McpTool(
            name = "list_notes",
            description = "A cluster's operator notes, newest first.",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpSchema.CallToolResult listNotes(@McpToolParam(required = true) String clusterId) {
        return McpErrors.guard(() -> notes.list(McpArgs.uuid("clusterId", clusterId)));
    }
}
```

The tool calls the same service as the controller, so the caller's grants and the
audit trail apply unchanged. A tool that changes something takes `dryRun` (default
true) and, if it destroys anything, `confirm` equal to the subject's name.

## 4. Register it

`src/main/java/io/github/sudoitir/artemisstudio/app/StudioFeatures.java` is the one list:

```java
@Import({ /* … */ NotesFeature.class })
public class StudioFeatures {
    public static List<FeatureDescriptor> descriptors() {
        return List.of(/* … */ NotesModule.DESCRIPTOR);
    }
}
```

Give it a module test, which starts it with its direct dependencies only:

```java
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
class NotesModuleTest extends ModuleIntegrationTest {

    @MockitoBean
    ScopeHierarchy scopeHierarchy;
}
```

Then regenerate what the frontend reads from the backend:

```bash
./mvnw test -Dtest='OpenApiSnapshotTest,ManifestSnapshotTest'   # web/openapi.json, web/manifest.snapshot.json
npm --prefix web run gen:api                                     # web/src/kernel/api/schema.d.ts
```

## 5. Frontend

```
web/src/features/notes/
  api.ts          hooks and query keys
  NotesView.tsx
  feature.ts      what it contributes
```

```ts
// api.ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { clusterKey, request, type ApiError } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

export type NoteView = components['schemas']['NoteView'];

const notesKey = (clusterId: string) => clusterKey(clusterId, 'notes');

export function useNotes(clusterId: string) {
  return useQuery<NoteView[], ApiError>({
    queryKey: notesKey(clusterId),
    queryFn: () => request(`/clusters/${clusterId}/notes`),
  });
}

export function useAddNote(clusterId: string) {
  const qc = useQueryClient();
  return useMutation<NoteView, ApiError, { text: string }>({
    mutationFn: (body) => request(`/clusters/${clusterId}/notes`, { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: notesKey(clusterId) }),
  });
}
```

```tsx
// NotesView.tsx
import { useState } from 'react';
import { Alert, Button, Stack, Text, Textarea, Title } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { useAddNote, useNotes } from './api.ts';

export function NotesView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const notes = useNotes(clusterId);
  const add = useAddNote(clusterId);
  const [text, setText] = useState('');

  return (
    <Stack gap="sm" maw={640}>
      <Title order={3}>Notes</Title>
      <Textarea label="New note" value={text} onChange={(e) => setText(e.currentTarget.value)} />
      <Button loading={add.isPending} onClick={() => add.mutate({ text }, { onSuccess: () => setText('') })}>
        Add note
      </Button>
      {add.isError ? <Alert color="red" title={add.error.title}>{add.error.message}</Alert> : null}
      {notes.data?.map((note) => <Text key={note.id}>{note.text}</Text>)}
    </Stack>
  );
}
```

```ts
// feature.ts
import { IconNotes } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { NotesView } from './NotesView.tsx';

const notesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'notes',
  component: featureView('notes', NotesView),
});

export const notesFeature = defineFeature({
  contract: CONTRACT,
  id: 'notes',
  routes: { cluster: [notesRoute] },
  nav: [{ group: 'activity', order: 30, label: 'Notes', icon: IconNotes, path: 'notes', permission: 'note:read' }],
});
```

Register it in `web/src/app/features.ts` (`FEATURES`) and add `'notes'` to
`FEATURE_IDS` in `web/src/kernel/feature.ts`; a test holds that list to the
backend's manifest snapshot. `featureView` is what makes `/clusters/…/notes` explain
itself when the feature is off.

## What else a plugin can contribute

| Backend | How |
|---|---|
| Runtime settings | a `SettingsContribution` bean, keys named `notes.*`, listed in the descriptor's `settingKey` |
| Scheduled work | a `ScheduledJob` bean (`ScheduledJob.fixedDelay` / `cron`) |
| A live stream topic | `.streamTopic(TopicDef.signal("notes"))`, published with `SseHub.publish(clusterId, "notes")` |
| Broker notifications | a `BrokerEventSink` bean |
| A check during cluster registration | a `RegistrationCheckContributor` bean |
| Another feature it needs | `.require("queues")` and that module in `allowedDependencies` |

| Frontend (`defineFeature`) | How |
|---|---|
| Refresh on a topic | `streamTopics: { notes: ({ clusterId, invalidate }) => invalidate(clusterKey(clusterId, 'notes')) }` |
| A section on Settings | `slots: { 'settings.sections': [{ id: 'notes', order: 80, title: 'Notes', Component }] }` |
| A panel elsewhere | other slots: `cluster.header`, `queue.detail.panels`, `metrics.panels`, `admin.tabs`, `account.sections`, … |
| Command palette | `palette`: a component that calls `report(groups)` |

A plugin never imports another feature's internals. It reaches another feature only
through that feature's `index.ts` along an edge allowed in `web/eslint.config.js`,
and on the backend only through a declared dependency. `just verify` fails otherwise.

## Keep a plugin in its own repository

Studio composes plugins at build time, so an external plugin is kept separately and
added to a Studio checkout before the build. Mirror Studio's paths in the plugin's
repository:

```
notes-plugin/
  src/main/java/io/github/sudoitir/artemisstudio/feature/notes/…
  src/main/resources/db/changelog/feature/notes/…
  src/test/java/io/github/sudoitir/artemisstudio/feature/notes/…
  web/src/features/notes/…
```

and overlay it onto the checkout:

```bash
git clone https://github.com/sudoitir/artemis-studio studio
cp -r notes-plugin/src notes-plugin/web studio/
# then the registration lines from steps 2, 4 and 5 — keep them as a patch in the plugin's repository:
git -C studio apply ../notes-plugin/register.patch
cd studio && just verify && docker build -t my-studio .
```

The build runs the module, boundary, schema and contract tests over the plugin as if
it had always been there, and the image carries it like any built-in feature.

## Turn it off

```bash
ARTEMIS_STUDIO_FEATURES_NOTES_ENABLED=false
```

Its screen explains that it is off, its API answers `404 feature-disabled`, its tool
disappears, and its table stays, so turning it back on is a restart.

import { useState } from 'react';
import { Button, Group, Stack, Text, Textarea } from '@mantine/core';
import { useCan } from '@artemis-studio/plugin-sdk';

import { ID, useAddNote, useDeleteNote, useQueueNotes } from './api.ts';

/** The notes on one queue, and — for someone allowed to — a way to add one. */
export function NotesList({ clusterId, queue }: { clusterId: string; queue: string }) {
  const notes = useQueueNotes(clusterId, queue);
  const add = useAddNote(clusterId, queue);
  const remove = useDeleteNote(clusterId);
  const { can } = useCan();
  const [body, setBody] = useState('');
  const canWrite = can(`${ID}:write`, clusterId);

  return (
    <Stack gap="xs">
      {notes.isError ? <Text size="sm">Notes could not be loaded: {notes.error.message}</Text> : null}
      {notes.data?.length === 0 ? (
        <Text size="sm" c="dimmed">
          No notes on {queue} yet. A note is for the next operator: why this queue is odd, who owns it.
        </Text>
      ) : null}
      {notes.data?.map((note) => (
        <Group key={note.id} justify="space-between" align="flex-start" wrap="nowrap">
          <Stack gap={0}>
            <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
              {note.body}
            </Text>
            <Text size="xs" c="dimmed">
              {note.author}, {new Date(note.createdAt).toLocaleString()}
            </Text>
          </Stack>
          {canWrite ? (
            <Button size="compact-xs" variant="subtle" loading={remove.isPending && remove.variables === note.id} onClick={() => remove.mutate(note.id)}>
              Delete
            </Button>
          ) : null}
        </Group>
      ))}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          add.mutate(body, { onSuccess: () => setBody('') });
        }}
      >
        <Stack gap={4}>
          <Textarea
            label="Add a note"
            value={body}
            onChange={(e) => setBody(e.currentTarget.value)}
            autosize
            minRows={2}
            disabled={!canWrite}
            description={canWrite ? undefined : `Adding notes needs the ${ID}:write permission on this cluster.`}
            error={add.error?.message}
          />
          <Button type="submit" size="xs" w="fit-content" loading={add.isPending} disabled={!canWrite}>
            Add note
          </Button>
        </Stack>
      </form>
    </Stack>
  );
}

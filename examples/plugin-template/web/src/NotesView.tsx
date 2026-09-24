import { Anchor, Stack, Table, Text, Title } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useRecentNotes } from './api.ts';

/** The plugin's page in a cluster: the latest notes across its queues. */
export function NotesView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const notes = useRecentNotes(clusterId);
  return (
    <Stack gap="md">
      <Title order={3}>Notes</Title>
      {notes.isError ? <Text size="sm">Notes could not be loaded: {notes.error.message}</Text> : null}
      {notes.data?.length === 0 ? (
        <Text size="sm" c="dimmed">
          No notes on this cluster yet. Open a queue and add one from its details.
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Queue</Table.Th>
              <Table.Th>Note</Table.Th>
              <Table.Th>By</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {notes.data?.map((note) => (
              <Table.Tr key={note.id}>
                <Table.Td>
                  <Anchor component={Link} to={`/clusters/${clusterId}/queues`} search={{ queue: note.queue } as never}>
                    {note.queue}
                  </Anchor>
                </Table.Td>
                <Table.Td>{note.body}</Table.Td>
                <Table.Td>{note.author}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

import { Badge, Drawer, Group, Stack, Table, Text } from "@mantine/core";

import type { QueueView } from "../api/client.ts";

/** Read-only per-node breakdown for one queue row. Message ops are Phase 3. */
export function QueueDetailDrawer({
  queue,
  onClose,
}: {
  queue: QueueView | null;
  onClose: () => void;
}) {
  return (
    <Drawer
      opened={queue !== null}
      onClose={onClose}
      position="right"
      size="lg"
      title={queue ? `${queue.address} / ${queue.queueName}` : ""}
    >
      {queue ? (
        <Stack gap="md">
          <Group gap="xs">
            <Badge variant="light">{queue.routingType}</Badge>
            <Badge variant="light" color="gray">
              {queue.durable ? "durable" : "non-durable"}
            </Badge>
            <Badge variant="light" color="gray">
              {queue.nodesPresent}/{queue.nodesTotal} nodes
            </Badge>
          </Group>

          {/* A node name is broker-supplied and can be long; the library's own
              container keeps the overflow in the table rather than the drawer. */}
          <Table.ScrollContainer minWidth={420} type="native">
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Node</Table.Th>
                  <Table.Th ta="end">Depth</Table.Th>
                  <Table.Th ta="end">Consumers</Table.Th>
                  <Table.Th ta="end">Delivering</Table.Th>
                  <Table.Th ta="end">Scheduled</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {queue.perNode.map((cell) => (
                  <Table.Tr key={cell.nodeId}>
                    <Table.Td>
                      {cell.nodeName}
                      {cell.stale ? (
                        <Text span size="xs" c="dimmed">
                          {" "}
                          · stale
                        </Text>
                      ) : null}
                    </Table.Td>
                    <Table.Td ta="end">{cell.messageCount}</Table.Td>
                    <Table.Td ta="end">{cell.consumerCount}</Table.Td>
                    <Table.Td ta="end">{cell.deliveringCount}</Table.Td>
                    <Table.Td ta="end">{cell.scheduledCount}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Stack>
      ) : null}
    </Drawer>
  );
}

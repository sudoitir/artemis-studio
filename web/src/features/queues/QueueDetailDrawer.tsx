import { Badge, Button, Drawer, Group, Stack, Table, Text } from "@mantine/core";
import { Link, useParams } from "@tanstack/react-router";

import { type QueueView } from './api.ts';
import { useSlot } from "../../kernel/slots.ts";
import { QueueLifecycleActions } from "./QueueLifecycleActions.tsx";

/**
 * Per-node breakdown for one queue row, its lifecycle actions, a jump into the message browser,
 * and whatever the enabled features add below (`queue.detail.panels`), such as its recent history.
 */
export function QueueDetailDrawer({
  queue,
  onClose,
}: {
  queue: QueueView | null;
  onClose: () => void;
}) {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const panels = useSlot("queue.detail.panels");

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
          <Group gap="xs" justify="space-between">
            <Group gap="xs">
              <Badge variant="light">{queue.routingType}</Badge>
              <Badge variant="light" color="gray">
                {queue.durable ? "durable" : "non-durable"}
              </Badge>
              <Badge variant="light" color="gray">
                {queue.nodesPresent}/{queue.nodesTotal} nodes
              </Badge>
            </Group>
            <Button
              size="xs"
              variant="light"
              component={Link}
              to={`/clusters/${clusterId}/queues/${encodeURIComponent(queue.queueName)}/messages`}
              onClick={onClose}
            >
              Browse messages
            </Button>
          </Group>

          <QueueLifecycleActions clusterId={clusterId} queue={queue} onClose={onClose} />

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

          {panels.map(({ id, Component }) => (
            <Component key={id} clusterId={clusterId} queueName={queue.queueName} onClose={onClose} />
          ))}
        </Stack>
      ) : null}
    </Drawer>
  );
}

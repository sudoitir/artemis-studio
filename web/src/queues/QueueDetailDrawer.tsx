import { useMemo } from "react";
import { Badge, Button, Drawer, Group, Stack, Table, Text } from "@mantine/core";
import { Link, useNavigate, useParams } from "@tanstack/react-router";

import { useMetrics, type QueueView } from "../api/client.ts";
import { DepthChart } from "../metrics/DepthChart.tsx";
import { QueueLifecycleActions } from "./QueueLifecycleActions.tsx";
import { ThroughputChart } from "../metrics/ThroughputChart.tsx";
import { rangeSpec } from "../metrics/ranges.ts";

/** The drawer always shows the last hour; a longer view is the metrics page's job. */
const DRAWER_RANGE = "1h" as const;

/** Per-node breakdown for one queue row, its lifecycle actions, and a jump into the message browser. */
export function QueueDetailDrawer({
  queue,
  onClose,
}: {
  queue: QueueView | null;
  onClose: () => void;
}) {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const navigate = useNavigate();
  // The drawer's own window is a fixed hour, quantized to the same bucket the
  // metrics view uses so the two agree about where a bucket starts (ADR-0055).
  const spec = rangeSpec(DRAWER_RANGE);
  const { from, to, fromMs, toMs } = useMemo(() => {
    const end = Math.floor(Date.now() / spec.stepMs) * spec.stepMs;
    const start = end - spec.windowMs;
    return {
      from: new Date(start).toISOString(),
      to: new Date(end).toISOString(),
      fromMs: start,
      toMs: end,
    };
  }, [spec.stepMs, spec.windowMs]);
  const metrics = useMetrics(
    clusterId,
    {
      metrics: ["messageCount", "messagesAdded", "messagesAcked"],
      subjectType: "QUEUE",
      subject: queue?.queueName,
      from,
      to,
    },
    false,
    queue !== null,
  );
  const byName = (name: string) => metrics.data?.series.find((s) => s.metric === name);
  const syncId = `queue-drawer-${queue?.queueName ?? "none"}`;

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
            <Group gap="xs">
              {/* Navigated rather than linked: the untyped router cannot type a
                  search reducer on `Link`, and the whole point here is to carry
                  `?subject=` across. */}
              <Button
                size="xs"
                variant="subtle"
                onClick={() => {
                  onClose();
                  void navigate({
                    to: `/clusters/${clusterId}/metrics`,
                    search: { subject: queue.queueName } as never,
                  });
                }}
              >
                History
              </Button>
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

          <Stack gap={4}>
            <Text size="xs" fw={600} c="dimmed">
              Depth · last hour
            </Text>
            <DepthChart
              series={byName("messageCount")}
              range={DRAWER_RANGE}
              from={fromMs}
              to={toMs}
              syncId={syncId}
            />
          </Stack>
          <Stack gap={4}>
            <Text size="xs" fw={600} c="dimmed">
              Throughput · last hour
            </Text>
            <ThroughputChart
              added={byName("messagesAdded")}
              acked={byName("messagesAcked")}
              range={DRAWER_RANGE}
              from={fromMs}
              to={toMs}
              syncId={syncId}
            />
          </Stack>
        </Stack>
      ) : null}
    </Drawer>
  );
}

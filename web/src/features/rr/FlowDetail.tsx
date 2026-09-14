import { Alert, Badge, Drawer, Group, Loader, Stack, Table, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';

import { useRrFlow } from './api.ts';
import { stateColorVar, stateLabel } from './rrState.ts';
import type { components } from '../../kernel/api/schema.d.ts';
import { RedactionMarks, WithheldNotice } from '../../ui/RedactedValue.tsx';

type RedactionView = components['schemas']['RedactionView'];
type WithheldView = components['schemas']['WithheldView'];

/**
 * One timeline event's detail. A captured payload is shown as the governed preview with its marks, or as the
 * reason it was omitted; any other detail is shown as it came.
 */
function EventDetail({ detail }: { detail: Record<string, unknown> }) {
  if (typeof detail.payloadOmitted === 'string') {
    return (
      <Text size="xs" c="dimmed">
        {detail.payloadOmitted}
      </Text>
    );
  }
  if ('bodyPreview' in detail) {
    const redactions = Array.isArray(detail.redactions) ? (detail.redactions as RedactionView[]) : [];
    const withheld = Array.isArray(detail.withheld) ? (detail.withheld as WithheldView[]) : [];
    return (
      <Stack gap={4}>
        <RedactionMarks redactions={redactions} />
        <WithheldNotice withheld={withheld} />
        <CodeHighlight
          code={typeof detail.bodyPreview === 'string' ? detail.bodyPreview : '(no preview)'}
          language="text"
        />
        {detail.truncated === true ? (
          <Text size="xs" c="dimmed">
            Cut to the payload capture limit.
          </Text>
        ) : null}
      </Stack>
    );
  }
  return <CodeHighlight code={JSON.stringify(detail, null, 2)} language="json" />;
}

/** The `rr_event` timeline and any captured payload for one flow, mirroring {@code MessageDetailPanel}. */
export function FlowDetail({
  clusterId,
  flowId,
  onClose,
}: {
  clusterId: string;
  flowId: string | null;
  onClose: () => void;
}) {
  const detail = useRrFlow(clusterId, flowId ?? undefined);
  const f = detail.data;

  return (
    <Drawer
      opened={flowId !== null}
      onClose={onClose}
      position="right"
      size="lg"
      title={f ? `Flow on ${f.requestAddress}` : 'Flow'}
    >
      {detail.isPending ? (
        <Loader size="sm" />
      ) : detail.isError ? (
        <Alert color="red" variant="light" title={detail.error.title}>
          {detail.error.message}
        </Alert>
      ) : f ? (
        <Stack gap="md">
          <Group gap="xs">
            <Badge variant="light" style={{ color: stateColorVar(f.state) }}>
              {stateLabel(f.state)}
            </Badge>
            <Badge variant="light" color="gray">
              {f.replyKind === 'TEMP_QUEUE' ? 'temp reply queue' : 'shared reply queue'}
            </Badge>
          </Group>

          <Table withRowBorders={false} verticalSpacing={2}>
            <Table.Tbody>
              <Table.Tr>
                <Table.Td w="35%">
                  <Text size="xs" c="dimmed">
                    Requested at
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs" ff="monospace">
                    {f.requestedAt}
                  </Text>
                </Table.Td>
              </Table.Tr>
              {f.repliedAt ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Replied at
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.repliedAt}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.deadlineAt ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Deadline
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.deadlineAt}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.latencyMs != null ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Latency
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Stack gap={2}>
                      <Text size="xs" ff="monospace">
                        {f.latencyMs}ms
                        {f.latencySource === 'OBSERVED' && f.latencyBoundMs != null
                          ? ` ± ${f.latencyBoundMs}ms`
                          : ''}
                      </Text>
                      {/* Which clock measured it is part of the measurement: an
                          OBSERVED figure is the gap between two sample ticks and
                          cannot resolve anything shorter (ADR-0053). */}
                      <Text size="xs" c="dimmed">
                        {f.latencySource === 'MESSAGE_TIMESTAMPS'
                          ? 'from the messages’ own timestamps, normalised onto Studio’s clock'
                          : 'observed between sample ticks, so it cannot resolve anything shorter'}
                      </Text>
                    </Stack>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.requestSkewMs != null || f.replySkewMs != null ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Clock skew
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="orange">
                      {f.requestSkewMs != null
                        ? `the request claimed to be produced ${f.requestSkewMs}ms in the future`
                        : ''}
                      {f.requestSkewMs != null && f.replySkewMs != null ? '; ' : ''}
                      {f.replySkewMs != null
                        ? `the reply claimed to be produced ${f.replySkewMs}ms in the future`
                        : ''}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.correlationId ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Correlation id
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.correlationId}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {f.replyDestination ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Reply destination
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" ff="monospace">
                      {f.replyDestination}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ) : null}
            </Table.Tbody>
          </Table>

          <Text size="xs" fw={600} c="dimmed">
            Timeline
          </Text>
          {f.events && f.events.length > 0 ? (
            <Stack gap="xs">
              {f.events.map((e) => (
                <Stack key={e.seq} gap={2}>
                  <Group gap="xs">
                    <Text size="xs" ff="monospace">
                      {e.ts}
                    </Text>
                    <Badge size="xs" variant="light">
                      {e.kind}
                    </Badge>
                  </Group>
                  {e.detail ? <EventDetail detail={e.detail} /> : null}
                </Stack>
              ))}
            </Stack>
          ) : (
            <Text size="xs" c="dimmed">
              No events recorded for this flow.
            </Text>
          )}
        </Stack>
      ) : null}
    </Drawer>
  );
}

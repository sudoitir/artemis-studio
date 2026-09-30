import type { ReactNode } from 'react';
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
function EventDetail({ detail }: Readonly<{ detail: Record<string, unknown> }>) {
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

type Flow = NonNullable<ReturnType<typeof useRrFlow>['data']>;

/** One fact about the flow: a dimmed label beside its value. */
function FactRow({ label, first, children }: Readonly<{ label: string; first?: boolean; children: ReactNode }>) {
  return (
    <Table.Tr>
      <Table.Td w={first ? '35%' : undefined}>
        <Text size="xs" c="dimmed">
          {label}
        </Text>
      </Table.Td>
      <Table.Td>{children}</Table.Td>
    </Table.Tr>
  );
}

const Mono = ({ children }: Readonly<{ children: ReactNode }>) => (
  <Text size="xs" ff="monospace">
    {children}
  </Text>
);

/** Which clock measured the latency is part of the measurement: an OBSERVED figure is the gap between two sample ticks. */
function LatencyRow({ f }: Readonly<{ f: Flow }>) {
  const bound = f.latencySource === 'OBSERVED' && f.latencyBoundMs != null ? ` ± ${f.latencyBoundMs}ms` : '';
  return (
    <FactRow label="Latency">
      <Stack gap={2}>
        <Mono>
          {f.latencyMs}ms
          {bound}
        </Mono>
        {/* Which clock measured it is part of the measurement: an
            OBSERVED figure is the gap between two sample ticks and
            cannot resolve anything shorter (ADR-0053). */}
        <Text size="xs" c="dimmed">
          {f.latencySource === 'MESSAGE_TIMESTAMPS'
            ? 'from the messages’ own timestamps, normalised onto Studio’s clock'
            : 'observed between sample ticks, so it cannot resolve anything shorter'}
        </Text>
      </Stack>
    </FactRow>
  );
}

/** What the clocks on the two sides claim, when either is ahead of Studio's. */
function SkewRow({ f }: Readonly<{ f: Flow }>) {
  const request =
    f.requestSkewMs != null ? `the request claimed to be produced ${f.requestSkewMs}ms in the future` : '';
  const separator = f.requestSkewMs != null && f.replySkewMs != null ? '; ' : '';
  const reply = f.replySkewMs != null ? `the reply claimed to be produced ${f.replySkewMs}ms in the future` : '';
  return (
    <FactRow label="Clock skew">
      <Text size="xs" c="orange">
        {request}
        {separator}
        {reply}
      </Text>
    </FactRow>
  );
}

/** When the flow was requested and replied, its deadline and latency, and how it is identified. */
function FlowFacts({ f }: Readonly<{ f: Flow }>) {
  return (
    <Table withRowBorders={false} verticalSpacing={2}>
      <Table.Tbody>
        <FactRow label="Requested at" first>
          <Mono>{f.requestedAt}</Mono>
        </FactRow>
        {f.repliedAt ? (
          <FactRow label="Replied at">
            <Mono>{f.repliedAt}</Mono>
          </FactRow>
        ) : null}
        {f.deadlineAt ? (
          <FactRow label="Deadline">
            <Mono>{f.deadlineAt}</Mono>
          </FactRow>
        ) : null}
        {f.latencyMs != null ? <LatencyRow f={f} /> : null}
        {f.requestSkewMs != null || f.replySkewMs != null ? <SkewRow f={f} /> : null}
        {f.correlationId ? (
          <FactRow label="Correlation id">
            <Mono>{f.correlationId}</Mono>
          </FactRow>
        ) : null}
        {f.replyDestination ? (
          <FactRow label="Reply destination">
            <Mono>{f.replyDestination}</Mono>
          </FactRow>
        ) : null}
      </Table.Tbody>
    </Table>
  );
}

/** The flow's events in order, each with its detail. */
function Timeline({ events }: Readonly<{ events: Flow['events'] }>) {
  if (!events || events.length === 0) {
    return (
      <Text size="xs" c="dimmed">
        No events recorded for this flow.
      </Text>
    );
  }
  return (
    <Stack gap="xs">
      {events.map((e) => (
        <Stack key={e.seq} gap={2}>
          <Group gap="xs">
            <Mono>{e.ts}</Mono>
            <Badge size="xs" variant="light">
              {e.kind}
            </Badge>
          </Group>
          {e.detail ? <EventDetail detail={e.detail} /> : null}
        </Stack>
      ))}
    </Stack>
  );
}

/** The flow itself: its state, its facts and its timeline. */
function FlowContent({ f }: Readonly<{ f: Flow }>) {
  return (
    <Stack gap="md">
      <Group gap="xs">
        <Badge variant="light" style={{ color: stateColorVar(f.state) }}>
          {stateLabel(f.state)}
        </Badge>
        <Badge variant="light" color="gray">
          {f.replyKind === 'TEMP_QUEUE' ? 'temp reply queue' : 'shared reply queue'}
        </Badge>
      </Group>

      <FlowFacts f={f} />

      <Text size="xs" fw={600} c="dimmed">
        Timeline
      </Text>
      <Timeline events={f.events} />
    </Stack>
  );
}

/** What stands in for the flow while it loads or fails to load. */
function flowNotice(detail: ReturnType<typeof useRrFlow>): ReactNode {
  if (detail.isPending) return <Loader size="sm" />;
  if (detail.isError) {
    return (
      <Alert color="red" variant="light" title={detail.error.title}>
        {detail.error.message}
      </Alert>
    );
  }
  return null;
}

/** The `rr_event` timeline and any captured payload for one flow, mirroring {@code MessageDetailPanel}. */
export function FlowDetail({
  clusterId,
  flowId,
  onClose,
}: Readonly<{
  clusterId: string;
  flowId: string | null;
  onClose: () => void;
}>) {
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
      {flowNotice(detail) ?? (f ? <FlowContent f={f} /> : null)}
    </Drawer>
  );
}

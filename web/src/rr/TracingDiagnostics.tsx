import { Alert, Anchor, List, Stack, Table, Text } from '@mantine/core';

import { elapsedLabel, useServerNow } from '../app/time.ts';
import { useRrDiagnostics, type ExpectationDiagnosticsView } from '../api/client.ts';

/** `4s ago`, `3m ago` — the shared duration label, plus the word. */
function ago(iso: string | null | undefined, now: number): string {
  if (!iso) return 'never';
  return `${elapsedLabel(now - Date.parse(iso))} ago`;
}

/**
 * One traced address's last tick, in a sentence an operator can act on.
 *
 * The interesting states are all "Studio looked and found nothing" versus "Studio
 * never looked", which the flows list alone cannot distinguish.
 */
export function ExpectationStatus({
  status,
  now,
}: {
  status: ExpectationDiagnosticsView | undefined;
  now: number;
}) {
  if (!status) {
    return (
      <Text size="xs" c="dimmed">
        not sampled yet
      </Text>
    );
  }
  if (!status.enabled) {
    return (
      <Text size="xs" c="dimmed">
        disabled
      </Text>
    );
  }
  const trouble = status.skipped.length > 0 || status.lastError !== null;
  return (
    <Stack gap={2}>
      <Text size="xs" c={trouble ? 'orange' : 'dimmed'}>
        {status.nodesSampled}/{status.nodesTotal} nodes · sampled {ago(status.lastSuccessAt, now)}
      </Text>
      {status.skipped.length > 0 ? (
        <Text size="xs" c="orange">
          {status.skipped[0]}
        </Text>
      ) : null}
      {status.rateExceedsInterval ? (
        <Text size="xs" c="orange">
          asks for more samples than the sampler interval delivers
        </Text>
      ) : null}
    </Stack>
  );
}

/**
 * Why the Flows tab is empty.
 *
 * A bare `0 flows` reads identically whether nothing was sent, nothing could be
 * browsed, or every request was consumed faster than the sampler ticks — three
 * situations with three different answers. This shows what the sampler actually
 * did and the reasons ranked most-likely-first, each with its remedy.
 */
export function TracingDiagnostics({ clusterId }: { clusterId: string }) {
  const diagnostics = useRrDiagnostics(clusterId);
  const now = useServerNow();

  if (diagnostics.isPending) {
    return (
      <Text size="sm" c="dimmed">
        Checking why…
      </Text>
    );
  }
  if (diagnostics.isError) {
    return (
      <Text size="sm" c="dimmed">
        Could not read tracing diagnostics: {diagnostics.error.message}
      </Text>
    );
  }

  const d = diagnostics.data;
  const skewed = d.clock.verdict === 'BROKER_SKEWED' || d.clock.verdict === 'STUDIO_SUSPECT';

  return (
    <Stack gap="sm">
      <Alert color="gray" variant="light" title="No flows to show — here is what Studio did">
        <Stack gap="xs">
          <Text size="sm">
            Tracing browses every {Math.round(d.sampleIntervalMs / 1000)}s over the Core client, on{' '}
            {d.nodesWithCoreEndpoint} of {d.nodesTotal} node
            {d.nodesTotal === 1 ? '' : 's'}. Reasons you may be seeing nothing, most likely first:
          </Text>
          <List size="sm" spacing={4}>
            {d.reasons.map((r) => (
              <List.Item key={r.code}>
                {r.summary} <Text span size="xs" c="dimmed">— {r.remedy}</Text>
              </List.Item>
            ))}
          </List>
        </Stack>
      </Alert>

      {skewed ? (
        <Alert color="yellow" variant="light" title="A clock disagrees with Studio's">
          {d.clock.verdict === 'STUDIO_SUSPECT'
            ? `Every measured node reports the same offset, so the common factor is Studio's own host rather than the brokers. Check the clock where Studio runs.`
            : `${d.clock.skewedNodes.join(', ')} disagree with Studio by up to ${d.clock.worstOffsetMs}ms. Deadlines and latencies involving those nodes are corrected, but a producer or consumer on the same clock is not.`}
        </Alert>
      ) : null}

      {d.expectations.length > 0 ? (
        <Table.ScrollContainer minWidth={560} type="native">
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Request address</Table.Th>
                <Table.Th>Last tick</Table.Th>
                <Table.Th>Browsed</Table.Th>
                <Table.Th>Observed</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {d.expectations.map((e) => (
                <Table.Tr key={e.expectationId}>
                  <Table.Td>
                    <Text size="sm" ff="monospace">
                      {e.requestAddress}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <ExpectationStatus status={e} now={now} />
                  </Table.Td>
                  <Table.Td>{e.messagesBrowsed}</Table.Td>
                  <Table.Td>{e.observations}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      ) : null}

      <Text size="xs" c="dimmed">
        Sampling is the design, not a limitation being worked around —{' '}
        <Anchor
          href="https://github.com/sudoitir/artemis-studio/blob/main/docs/adr/0030-rr-correlation-notification-anchored-browse-sampled.md"
          target="_blank"
          rel="noreferrer"
          size="xs"
        >
          ADR-0030
        </Anchor>{' '}
        explains the coverage ceiling and why browsing every message would not be
        broker-friendly.
      </Text>
    </Stack>
  );
}

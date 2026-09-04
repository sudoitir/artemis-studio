import { useState } from 'react';
import { Alert, Select, Stack, Tabs, Text, TextInput, Title } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useDebouncedValue } from '@mantine/hooks';

import { useCluster, useRrFlows } from '../api/client.ts';
import { useClusterStream } from '../api/stream.ts';
import { ExpectationsView } from './ExpectationsView.tsx';
import { FlowDetail } from './FlowDetail.tsx';
import { FlowsTable } from './FlowsTable.tsx';
import { LatencyPanel } from './LatencyPanel.tsx';
import { StuckPanel } from './StuckPanel.tsx';

const PAGE_SIZE = 100;

/**
 * Request-reply tracing: the flagship screen. When the cluster's notification
 * capability is unavailable (no NOTIFICATIONS, or no resolvable Core URL),
 * shows why rather than an empty flows list — same stance as {@code EventsView}.
 */
export function FlowsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as {
    tab?: string;
    state?: string;
    address?: string;
  };
  const navigate = useNavigate();

  const cluster = useCluster(clusterId);
  const notificationsCap = cluster.data?.capabilities.notifications;

  const [selectedFlow, setSelectedFlow] = useState<string | null>(null);
  const [address, setAddress] = useState(search.address ?? '');
  const [debouncedAddress] = useDebouncedValue(address, 250);

  useClusterStream(clusterId, ['rr']);

  const tab = search.tab ?? 'flows';
  const setTab = (v: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, tab: v ?? undefined }) });

  const flows = useRrFlows(clusterId, {
    state: search.state,
    address: debouncedAddress || undefined,
    size: PAGE_SIZE,
  });

  if (notificationsCap && notificationsCap.status !== 'AVAILABLE') {
    return (
      <Stack gap="sm">
        <Title order={3}>Request-reply tracing</Title>
        <Alert
          color={notificationsCap.status === 'UNKNOWN' ? 'blue' : 'yellow'}
          variant="light"
          title="Tracing not available"
        >
          {notificationsCap.reason}
        </Alert>
        {notificationsCap.brokerXmlSnippet ? (
          <CodeHighlight code={notificationsCap.brokerXmlSnippet} language="xml" />
        ) : null}
      </Stack>
    );
  }

  return (
    <Stack gap="sm">
      <Title order={3}>Request-reply tracing</Title>

      <Tabs value={tab} onChange={setTab}>
        <Tabs.List>
          <Tabs.Tab value="flows">Flows</Tabs.Tab>
          <Tabs.Tab value="stuck">Stuck</Tabs.Tab>
          <Tabs.Tab value="latency">Latency</Tabs.Tab>
          <Tabs.Tab value="expectations">Expectations</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="flows" pt="sm">
          <Stack gap="sm">
            <TextInput
              placeholder="Filter by address"
              value={address}
              onChange={(e) => setAddress(e.currentTarget.value)}
              size="xs"
              w={220}
            />
            <Select
              placeholder="Any state"
              size="xs"
              w={220}
              clearable
              value={search.state ?? null}
              onChange={(v) =>
                navigate({
                  to: '.',
                  search: (prev: Record<string, unknown>) => ({ ...prev, state: v || undefined }),
                })
              }
              data={[
                'AWAITING_REPLY',
                'COMPLETED',
                'TIMED_OUT',
                'ORPHANED',
                'RESPONDER_DROPPED',
                'ORPHANED_REPLY',
              ]}
            />
            {flows.isError ? (
              <Alert color="red" variant="light" title={flows.error.title}>
                {flows.error.message}
              </Alert>
            ) : (
              <>
                <Text size="xs" c="dimmed">
                  {flows.data?.count ?? 0} flow{(flows.data?.count ?? 0) === 1 ? '' : 's'}
                </Text>
                <FlowsTable flows={flows.data?.data ?? []} onSelect={setSelectedFlow} />
              </>
            )}
          </Stack>
        </Tabs.Panel>

        <Tabs.Panel value="stuck" pt="sm">
          <StuckPanel clusterId={clusterId} onSelect={setSelectedFlow} />
        </Tabs.Panel>

        <Tabs.Panel value="latency" pt="sm">
          <LatencyPanel clusterId={clusterId} />
        </Tabs.Panel>

        <Tabs.Panel value="expectations" pt="sm">
          <ExpectationsView clusterId={clusterId} />
        </Tabs.Panel>
      </Tabs>

      <FlowDetail clusterId={clusterId} flowId={selectedFlow} onClose={() => setSelectedFlow(null)} />
    </Stack>
  );
}

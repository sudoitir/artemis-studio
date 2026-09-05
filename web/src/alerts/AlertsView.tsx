import { Stack, Tabs, Title } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useFiringAlerts } from '../api/client.ts';
import { useClusterStream } from '../api/stream.ts';
import { FiringPanel } from './FiringPanel.tsx';
import { HistoryPanel } from './HistoryPanel.tsx';
import { RulesPanel } from './RulesPanel.tsx';

/** Firing alerts, history, and rule management for one cluster (alerting spec). */
export function AlertsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { tab?: string };
  const navigate = useNavigate();

  useClusterStream(clusterId, ['alerts']);
  const firing = useFiringAlerts(clusterId);

  const tab = search.tab ?? 'firing';
  const setTab = (v: string | null) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, tab: v ?? undefined }) });

  return (
    <Stack gap="md">
      <Title order={3}>Alerts</Title>

      <Tabs value={tab} onChange={setTab}>
        <Tabs.List>
          <Tabs.Tab value="firing">Firing{firing.data?.length ? ` (${firing.data.length})` : ''}</Tabs.Tab>
          <Tabs.Tab value="history">History</Tabs.Tab>
          <Tabs.Tab value="rules">Rules</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="firing" pt="md">
          <FiringPanel clusterId={clusterId} />
        </Tabs.Panel>
        <Tabs.Panel value="history" pt="md">
          <HistoryPanel clusterId={clusterId} />
        </Tabs.Panel>
        <Tabs.Panel value="rules" pt="md">
          <RulesPanel clusterId={clusterId} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

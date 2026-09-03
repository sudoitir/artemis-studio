import { useEffect, useState } from 'react';
import { AppShell, Burger, Group, Loader, Stack, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';

import { branding } from './branding.ts';
import { useClusters } from './api/client.ts';
import { ClusterRail } from './clusters/ClusterRail.tsx';
import { ClusterDetailPanel } from './clusters/ClusterDetailPanel.tsx';
import { EmptyState } from './clusters/RegisterCluster.tsx';

/**
 * The Phase 1 workspace: a cluster rail and one detail column. Routerless —
 * selection is local state; Phase 2 lifts it to the URL and wires TanStack
 * Router over this shell without rework.
 */
export function App() {
  const [navOpen, { toggle }] = useDisclosure();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const clusters = useClusters();

  // Auto-select the first cluster once, and follow deletions.
  useEffect(() => {
    if (!clusters.data) return;
    const ids = clusters.data.map((c) => c.id);
    if (selectedId && ids.includes(selectedId)) return;
    setSelectedId(ids[0] ?? null);
  }, [clusters.data, selectedId]);

  const hasClusters = (clusters.data?.length ?? 0) > 0;

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 260, breakpoint: 'sm', collapsed: { mobile: !navOpen } }}
      padding="lg"
    >
      <AppShell.Header>
        <Group h="100%" px="md" gap="xs">
          <Burger opened={navOpen} onClick={toggle} hiddenFrom="sm" size="sm" />
          <Text fw={600}>{branding.productName}</Text>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="md">
        {clusters.data ? (
          <ClusterRail
            clusters={clusters.data}
            selectedId={selectedId}
            onSelect={setSelectedId}
          />
        ) : (
          <Loader size="sm" />
        )}
      </AppShell.Navbar>

      <AppShell.Main>
        {!clusters.data ? (
          <Loader size="sm" />
        ) : !hasClusters ? (
          <EmptyState />
        ) : selectedId ? (
          <ClusterDetailPanel
            key={selectedId}
            clusterId={selectedId}
            onRemoved={() => setSelectedId(null)}
          />
        ) : (
          <Text c="dimmed">Select a cluster.</Text>
        )}

        <Stack maw={760} mt="xl">
          <Text size="xs" c="dimmed">
            {branding.trademarkNotice}
          </Text>
        </Stack>
      </AppShell.Main>
    </AppShell>
  );
}

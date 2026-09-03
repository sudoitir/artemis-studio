import { AppShell, Group, Text } from '@mantine/core';
import { Outlet } from '@tanstack/react-router';

import { branding } from '../branding.ts';
import { ClusterRailNav } from './ClusterRailNav.tsx';
import { CommandPalette } from '../palette/CommandPalette.tsx';

/**
 * The desktop workspace chrome: a fixed header, the cluster rail, and the routed
 * detail column. Desktop-first — no mobile breakpoint, no collapsing navbar
 * (steering decision, this session).
 */
export function RootLayout() {
  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 264, breakpoint: 0 }} padding="lg">
      <AppShell.Header>
        <Group h="100%" px="md" gap="xs" justify="space-between">
          <Text fw={600}>{branding.productName}</Text>
          <Text size="xs" c="dimmed">
            Press <kbd>⌘</kbd> <kbd>K</kbd>
          </Text>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="md">
        <ClusterRailNav />
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>

      <CommandPalette />
    </AppShell>
  );
}

import { AppShell, Group, ScrollArea, Text } from '@mantine/core';
import { useHotkeys, useReducedMotion } from '@mantine/hooks';
import { Outlet, useParams } from '@tanstack/react-router';

import { branding } from '../branding.ts';
import { ClusterRailNav } from './ClusterRailNav.tsx';
import { ClusterViewNav } from './ClusterViewNav.tsx';
import { CommandPalette } from '../palette/CommandPalette.tsx';
import { NavToggle } from './NavToggle.tsx';
import { useNavCollapsed } from './useNavCollapsed.ts';

const NAVBAR_ID = 'as-navbar';

/**
 * The desktop workspace chrome: a fixed header, the collapsible sidebar (cluster
 * switcher + per-cluster view nav, ADR-0034), and the routed detail column.
 * Desktop-first — no mobile breakpoint (`breakpoint: 0`).
 *
 * The sidebar collapses to a 64px icon rail rather than disappearing: `AppShell`'s
 * own `collapsed` prop removes the navbar's width entirely, which is the wrong
 * shape for a rail that stays present with icons. Animating `navbar.width`
 * instead lets `AppShell` transition both the navbar and the `Main` offset in
 * lockstep under one `transitionDuration` (design.md Decision 7).
 */
export function RootLayout() {
  const { collapsed, toggle } = useNavCollapsed();
  const reducedMotion = useReducedMotion();
  const { clusterId } = useParams({ strict: false }) as { clusterId?: string };

  useHotkeys([['mod+B', toggle]]);

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: collapsed ? 64 : 264, breakpoint: 0 }}
      padding="lg"
      transitionDuration={reducedMotion ? 0 : 180}
      transitionTimingFunction="cubic-bezier(0.2, 0, 0, 1)"
    >
      <AppShell.Header>
        <Group h="100%" px="md" gap="xs" justify="space-between">
          <Text fw={600}>{branding.productName}</Text>
          <Text size="xs" c="dimmed">
            <kbd>⌘</kbd> <kbd>K</kbd> search · <kbd>⌘</kbd> <kbd>B</kbd> sidebar
          </Text>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar id={NAVBAR_ID} p="md">
        <AppShell.Section>
          <NavToggle collapsed={collapsed} onToggle={toggle} controls={NAVBAR_ID} />
        </AppShell.Section>
        <AppShell.Section grow component={ScrollArea}>
          <ClusterRailNav collapsed={collapsed} />
          {clusterId ? <ClusterViewNav clusterId={clusterId} collapsed={collapsed} /> : null}
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>

      <CommandPalette />
    </AppShell>
  );
}

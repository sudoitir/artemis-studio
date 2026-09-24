import { useEffect } from 'react';
import { ActionIcon, AppShell, Button, Center, Group, Kbd, Loader, ScrollArea, Text, Tooltip } from '@mantine/core';
import { spotlight } from '@mantine/spotlight';
import { IconKeyboard, IconSearch } from '@tabler/icons-react';
import { useDocumentTitle, useHotkeys, useReducedMotion } from '@mantine/hooks';
import { Outlet, useLocation, useNavigate, useParams } from '@tanstack/react-router';

import styles from './RootLayout.module.css';
import { branding } from '../../branding.ts';
import { useMe } from '../auth/api.ts';
import { usePluginsChanged } from '../plugins/usePluginsChanged.tsx';
import { useSlot } from '../slots.ts';
import { ClusterViewNav } from './ClusterViewNav.tsx';
import { CommandPalette } from './CommandPalette.tsx';
import { FreshnessBar } from './FreshnessBar.tsx';
import { NavToggle } from './NavToggle.tsx';
import { UserMenu } from './UserMenu.tsx';
import { useNavCollapsed } from './useNavCollapsed.ts';
import { useCurrentView } from '../nav/currentView.ts';
import { useTitleParts } from './pageTitle.ts';
import { recordRecent } from './recents.ts';
import { setShortcutsHelpOpen } from '../keyboard/shortcuts.ts';
import { ShortcutsDialog } from '../keyboard/ShortcutsDialog.tsx';
import { useKeySequences } from '../keyboard/useKeySequences.ts';

const NAVBAR_ID = 'as-navbar';
const MAIN_ID = 'as-main';
const PUBLIC_PATHS = ['/login', '/change-password'];

/**
 * The desktop workspace chrome: a fixed header, the collapsible sidebar (the features' way between
 * clusters, then the open cluster's view nav, ADR-0034), and the routed detail column.
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
  const location = useLocation();
  const navigate = useNavigate();
  const isPublicRoute = PUBLIC_PATHS.includes(location.pathname);
  const me = useMe();
  const header = useSlot('shell.header');
  const navbar = useSlot('shell.navbar');

  useEffect(() => {
    if (isPublicRoute) return;
    if (me.isError && me.error.status === 401) {
      navigate({ to: '/login' });
    } else if (me.data?.mustChangePassword && location.pathname !== '/change-password') {
      navigate({ to: '/change-password' });
    }
  }, [isPublicRoute, me.isError, me.error, me.data, location.pathname, navigate]);

  // Every tab says what it holds (ADR-0109): an operator with six Studio tabs open finds the one
  // on prod's Queues without opening each.
  const view = useCurrentView();
  const titleParts = useTitleParts();
  useDocumentTitle(
    [titleParts.resource, view?.item?.label, view ? titleParts.cluster : undefined, branding.productName]
      .filter(Boolean)
      .join(' · '),
  );

  // Every place visited feeds the palette's Recent group: a view, or the resource open in it.
  const recentLabel = view?.item
    ? titleParts.resource
      ? `${titleParts.resource}`
      : view.item.label
    : undefined;
  useEffect(() => {
    if (!view?.item || !recentLabel) return;
    recordRecent(view.clusterId, {
      label: recentLabel,
      kind: titleParts.resource ? `In ${view.item.label}` : `View · ${view.groupLabel ?? ''}`.replace(/ · $/, ''),
      to: location.pathname,
      search: (location.search ?? {}) as Record<string, unknown>,
    });
    // The search is part of the place (the open queue), but a filter typed into it is not a new place.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view?.clusterId, view?.item, recentLabel, location.pathname]);

  useHotkeys([['mod+B', toggle]]);
  useKeySequences();
  usePluginsChanged();

  // Every hook above runs unconditionally on every render; only the JSX branches.
  if (isPublicRoute) {
    return <Outlet />;
  }

  if (me.isLoading) {
    return (
      <Center mih="100vh">
        <Loader />
      </Center>
    );
  }

  if (me.isError || me.data?.mustChangePassword) {
    // The effect above is already navigating away; render nothing in the meantime.
    return null;
  }

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: collapsed ? 64 : 264, breakpoint: 0 }}
      padding="lg"
      transitionDuration={reducedMotion ? 0 : 180}
      transitionTimingFunction="cubic-bezier(0.2, 0, 0, 1)"
    >
      <a href={`#${MAIN_ID}`} className={styles.skipLink}>
        Skip to content
      </a>
      <AppShell.Header>
        <Group h="100%" px="md" gap="xs" justify="space-between">
          <Group gap="xs">
            <Text fw={600}>{branding.productName}</Text>
            {header.map(({ id, Component }) => (
              <Component key={id} />
            ))}
          </Group>
          <Group gap="md" wrap="nowrap">
            <FreshnessBar />
            {/* A visible way into the palette: a shortcut nobody can see is one nobody finds. */}
            <Button
              size="xs"
              variant="default"
              leftSection={<IconSearch size={14} aria-hidden />}
              rightSection={
                <Kbd size="xs" visibleFrom="lg">
                  ⌘K
                </Kbd>
              }
              onClick={() => spotlight.open()}
              aria-keyshortcuts="Meta+K Control+K"
            >
              Search
            </Button>
            <Tooltip label="Keyboard shortcuts (?)">
              <ActionIcon
                variant="subtle"
                color="gray"
                aria-label="Keyboard shortcuts"
                aria-keyshortcuts="Shift+Slash"
                onClick={() => setShortcutsHelpOpen(true)}
              >
                <IconKeyboard size={18} aria-hidden />
              </ActionIcon>
            </Tooltip>
            <UserMenu me={me.data} />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar id={NAVBAR_ID} p="md">
        <AppShell.Section>
          <NavToggle collapsed={collapsed} onToggle={toggle} controls={NAVBAR_ID} />
        </AppShell.Section>
        <AppShell.Section grow component={ScrollArea}>
          {navbar.map(({ id, Component }) => (
            <Component key={id} collapsed={collapsed} />
          ))}
          {clusterId ? <ClusterViewNav clusterId={clusterId} collapsed={collapsed} /> : null}
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main id={MAIN_ID}>
        <Outlet />
      </AppShell.Main>

      <CommandPalette />
      <ShortcutsDialog />
    </AppShell>
  );
}

import { useEffect, useEffectEvent } from 'react';
import { AppShell, Button, Divider, Kbd, ScrollArea, Text } from '@mantine/core';
import { spotlight } from '@mantine/spotlight';
import { IconSearch } from '@tabler/icons-react';
import { useDocumentTitle, useHotkeys } from '@mantine/hooks';
import { Outlet, useLocation, useNavigate, useParams } from '@tanstack/react-router';

import styles from './RootLayout.module.css';
import { branding } from '../../branding.ts';
import { useMe } from '../auth/api.ts';
import { usePluginsChanged } from '../plugins/usePluginsChanged.tsx';
import { useSlot } from '../slots.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { ClusterViewNav } from './ClusterViewNav.tsx';
import { ColorSchemeToggle } from './ColorSchemeToggle.tsx';
import { CommandPalette } from './CommandPalette.tsx';
import { FreshnessBar } from './FreshnessBar.tsx';
import { NavToggle } from './NavToggle.tsx';
import { UserMenu } from './UserMenu.tsx';
import { useNavCollapsed } from './useNavCollapsed.ts';
import { useCurrentView } from '../nav/currentView.ts';
import { useTitleParts } from './pageTitle.ts';
import { recordRecent } from './recents.ts';
import { ShortcutsHelp } from '../keyboard/ShortcutsHelp.tsx';
import { useKeySequences } from '../keyboard/useKeySequences.ts';

const NAVBAR_ID = 'as-navbar';
const MAIN_ID = 'as-main';
const PUBLIC_PATHS = new Set(['/login', '/change-password', '/enrol-second-factor']);

/** The shell's transitions run on the theme's motion tokens, which reduced motion sets to zero. */
const shellVars = () => ({
  root: {
    '--app-shell-transition-duration': 'var(--as-duration-base)',
    '--app-shell-transition-timing-function': 'var(--as-ease)',
  },
});

/**
 * The desktop workspace chrome: a fixed header, the collapsible sidebar (the features' way between
 * clusters, then the open cluster's view nav, ADR-0034), and the routed detail column.
 * Desktop-first: no phone or tablet layout (ADR-0164).
 *
 * The sidebar collapses to an icon rail rather than disappearing: `AppShell`'s
 * own `collapsed` prop removes the navbar's width entirely, which is the wrong
 * shape for a rail that stays present with icons. Animating `navbar.width`
 * instead lets `AppShell` transition both the navbar and the `Main` offset in
 * lockstep under one duration (design.md Decision 7). Both widths are theme
 * tokens (`--as-nav-w`, `--as-nav-rail-w`), and the rail is also what a window
 * narrower than 64rem gets (`useNavCollapsed`).
 */
export function RootLayout() {
  const { collapsed, forced, toggle } = useNavCollapsed();
  const { clusterId } = useParams({ strict: false }) as { clusterId?: string };
  const location = useLocation();
  const navigate = useNavigate();
  const isPublicRoute = PUBLIC_PATHS.has(location.pathname);
  const me = useMe();
  const header = useSlot('shell.header');
  const navbar = useSlot('shell.navbar');

  useEffect(() => {
    if (isPublicRoute) return;
    if (me.isError && me.error.status === 401) {
      void navigate({ to: '/login' });
    } else if (me.data?.mustChangePassword && location.pathname !== '/change-password') {
      void navigate({ to: '/change-password' });
    } else if (me.data?.secondFactorEnrolmentRequired && !me.data.mustChangePassword) {
      // The session may enrol a second factor and do nothing else until it has (identity-and-sessions spec).
      void navigate({ to: '/enrol-second-factor' });
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
  const recentLabel = view?.item ? titleParts.resource || view.item.label : undefined;
  // The search is part of the place (the open queue), but a filter typed into it is not a new place:
  // the effect event reads it without making it a trigger.
  const record = useEffectEvent(() => {
    if (!view?.item || !recentLabel) return;
    recordRecent(view.clusterId, {
      label: recentLabel,
      kind: titleParts.resource ? `In ${view.item.label}` : `View · ${view.groupLabel ?? ''}`.replace(/ · $/, ''),
      to: location.pathname,
      search: (location.search ?? {}) as Record<string, unknown>,
    });
  });
  const recentCluster = view?.clusterId;
  const recentItem = view?.item;
  useEffect(() => {
    record();
  }, [recentCluster, recentItem, recentLabel, location.pathname]);

  useHotkeys([['mod+B', toggle]]);
  useKeySequences();
  usePluginsChanged();

  // Every hook above runs unconditionally on every render; only the JSX branches.
  if (isPublicRoute) {
    return <Outlet />;
  }

  if (me.isLoading) {
    return <LoadingState label="Loading Studio" blockSize="100dvh" />;
  }

  if (me.isError || me.data?.mustChangePassword || me.data?.secondFactorEnrolmentRequired) {
    // The effect above is already navigating away; render nothing in the meantime.
    return null;
  }

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: collapsed ? 'var(--as-nav-rail-w)' : 'var(--as-nav-w)', breakpoint: 0 }}
      padding="lg"
      vars={shellVars}
    >
      <a href={`#${MAIN_ID}`} className={styles.skipLink}>
        Skip to content
      </a>
      <AppShell.Header>
        <div className={styles.header}>
          <div className={styles.headerStart}>
            <img src="/favicon.svg" alt="" width={24} height={24} />
            <Text fw={600} truncate>
              {branding.productName}
            </Text>
            {header.map(({ id, Component }) => (
              <Component key={id} />
            ))}
          </div>
          <div className={styles.headerEnd}>
            <FreshnessBar />
            {/* The data's state on the left of the rule, the console's own controls on the right. */}
            <Divider orientation="vertical" />
            <ColorSchemeToggle />
            {/* A visible way into the palette: a shortcut nobody can see is one nobody finds. */}
            <Button
              size="xs"
              variant="default"
              leftSection={<IconSearch size={14} aria-hidden />}
              rightSection={<Kbd size="xs">⌘K</Kbd>}
              onClick={() => spotlight.open()}
              aria-keyshortcuts="Meta+K Control+K"
            >
              Search
            </Button>
            <ShortcutsHelp />
            <UserMenu me={me.data} />
          </div>
        </div>
      </AppShell.Header>

      <AppShell.Navbar id={NAVBAR_ID} p="sm">
        <AppShell.Section>
          <NavToggle collapsed={collapsed} forced={forced} onToggle={toggle} controls={NAVBAR_ID} />
        </AppShell.Section>
        <AppShell.Section grow component={ScrollArea}>
          {navbar.map(({ id, Component }) => (
            <Component key={id} collapsed={collapsed} />
          ))}
          {clusterId ? <ClusterViewNav clusterId={clusterId} collapsed={collapsed} /> : null}
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main id={MAIN_ID} className={styles.main}>
        <Outlet />
      </AppShell.Main>

      <CommandPalette />
    </AppShell>
  );
}

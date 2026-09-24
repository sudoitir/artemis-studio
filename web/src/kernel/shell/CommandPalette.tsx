import { useCallback, useEffect, useMemo, useState } from 'react';
import { Spotlight, type SpotlightActionData, type SpotlightActionGroupData } from '@mantine/spotlight';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch } from '@tabler/icons-react';
import { useNavigate, useParams } from '@tanstack/react-router';
import { useQueryClient } from '@tanstack/react-query';

import { isPollingPaused, refreshActiveQueries, setPollingPaused, usePollingPaused } from '../api/polling.ts';
import { useCan } from '../auth/useCan.ts';
import type { ModuleId, PaletteSource } from '../feature.ts';
import { useFeatures } from '../features.ts';
import { navGroups } from '../registry.ts';
import { readRecents } from './recents.ts';
import { setShortcutsHelpOpen } from '../keyboard/shortcuts.ts';

type Report = (feature: ModuleId, groups: SpotlightActionGroupData[]) => void;

/** Mounts one feature's palette source, with a `report` that stays the same across renders. */
function Source({ feature, Palette, clusterId, query, opened, onReport }: {
  feature: ModuleId;
  Palette: PaletteSource;
  clusterId?: string;
  query: string;
  opened: boolean;
  onReport: Report;
}) {
  const report = useCallback((groups: SpotlightActionGroupData[]) => onReport(feature, groups), [feature, onReport]);
  return <Palette clusterId={clusterId} query={query} opened={opened} report={report} />;
}

/**
 * ⌘K navigation across the console: jump to a view, a recent place, a cluster, or a queue by name, or
 * search a live view (ADR-0109). Mounted once in the root layout; the shortcut is registered by
 * {@link Spotlight}, and the header's Search button opens it too.
 *
 * The typed query reaches the features' sources, debounced, together with whether the palette is
 * open, so a source searches only while someone is looking — and only what Studio already holds.
 * A view the operator may not open is still listed, disabled, with the reason: the rail shows it the
 * same way.
 *
 * Refresh and pause live here rather than on a hotkey: the browser owns both
 * shortcuts an operator would reach for (⌘R and ⇧⌘R), and taking either would be
 * worse than not having one (ADR-0052).
 */
export function CommandPalette() {
  const navigate = useNavigate();
  const params = useParams({ strict: false }) as { clusterId?: string };
  const clusterId = params.clusterId;
  const qc = useQueryClient();
  const paused = usePollingPaused();
  const features = useFeatures();
  const { can, loading: grantsLoading } = useCan();
  const [query, setQuery] = useState('');
  const [debounced] = useDebouncedValue(query.trim(), 200);
  const [opened, setOpened] = useState(false);
  const [contributed, setContributed] = useState<Partial<Record<ModuleId, SpotlightActionGroupData[]>>>({});
  const onReport = useCallback<Report>(
    (feature, groups) => setContributed((prev) => ({ ...prev, [feature]: groups })),
    [],
  );

  // Read when the palette opens: a recent is recorded by every navigation, and re-reading storage on
  // each render would be work for a list nobody is looking at.
  const [recents, setRecents] = useState<ReturnType<typeof readRecents>>([]);
  useEffect(() => {
    if (opened && clusterId) setRecents(readRecents(clusterId));
  }, [opened, clusterId]);

  const groups = useMemo<SpotlightActionGroupData[]>(() => {
    const out: SpotlightActionGroupData[] = [];

    if (clusterId && recents.length > 0) {
      out.push({
        group: 'Recent',
        actions: recents.map((r) => ({
          id: `recent-${r.label}`,
          label: r.label,
          description: r.kind,
          onClick: () => navigate({ to: r.to, search: r.search as never }),
        })),
      });
    }

    out.push({
      group: 'Data',
      actions: [
        {
          id: 'refresh-data',
          label: 'Refresh data',
          description: 'Refetch everything on this screen',
          onClick: () => void refreshActiveQueries(qc),
        },
        {
          id: 'toggle-auto-refresh',
          label: paused ? 'Resume auto-refresh' : 'Pause auto-refresh',
          description: paused
            ? 'Start refetching on the usual interval again'
            : 'Stop refetching until you resume; does not survive a reload',
          onClick: () => setPollingPaused(!isPollingPaused()),
        },
        {
          id: 'keyboard-shortcuts',
          label: 'Keyboard shortcuts',
          description: 'Every shortcut, and the switch for the single-key ones',
          keywords: ['keys', 'hotkeys', 'help'],
          onClick: () => setShortcutsHelpOpen(true),
        },
      ],
    });

    // The views under the rail's own groups, so a view is found where the rail shows it.
    if (clusterId) {
      for (const navGroup of navGroups(features)) {
        out.push({
          group: navGroup.label,
          actions: navGroup.items.map((item): SpotlightActionData => {
            // Offered while grants load: a claim about permission is made only once it is known.
            const blocked = !grantsLoading && item.permission !== undefined && !can(item.permission, clusterId);
            return {
              id: `view-${item.path}`,
              label: item.label,
              description: blocked ? `Unavailable: needs the ${item.permission} permission on this cluster` : undefined,
              disabled: blocked,
              onClick: () => navigate({ to: `/clusters/${clusterId}/${item.path}` }),
            };
          }),
        });
      }
    }

    // In composition order, and only for features still enabled.
    for (const feature of features) out.push(...(contributed[feature.id] ?? []));
    return out;
  }, [clusterId, navigate, qc, paused, features, contributed, recents, can, grantsLoading]);

  return (
    <>
      {features.map((feature) =>
        feature.palette ? (
          <Source
            key={feature.id}
            feature={feature.id}
            Palette={feature.palette}
            clusterId={clusterId}
            query={debounced}
            opened={opened}
            onReport={onReport}
          />
        ) : null,
      )}
      <Spotlight
        actions={groups}
        shortcut={['mod + K']}
        query={query}
        onQueryChange={setQuery}
        onSpotlightOpen={() => setOpened(true)}
        onSpotlightClose={() => setOpened(false)}
        nothingFound={
          debounced.length > 0 && debounced.length < 2
            ? 'Type one more character to search queues'
            : 'Nothing matches. Queues are searched by name; live views are offered as searches.'
        }
        highlightQuery
        scrollable
        maxHeight={480}
        searchProps={{
          placeholder: 'Jump to a cluster, view, or queue…',
          'aria-label': 'Search views, clusters and queues',
          leftSection: <IconSearch size={16} aria-hidden />,
        }}
      />
    </>
  );
}

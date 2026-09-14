import { useCallback, useMemo, useState } from 'react';
import { Spotlight, type SpotlightActionGroupData } from '@mantine/spotlight';
import { useNavigate, useParams } from '@tanstack/react-router';
import { useQueryClient } from '@tanstack/react-query';

import { isPollingPaused, refreshActiveQueries, setPollingPaused, usePollingPaused } from '../api/polling.ts';
import type { FeatureId, PaletteSource } from '../feature.ts';
import { useFeatures } from '../features.ts';
import { navGroups } from '../registry.ts';

type Report = (feature: FeatureId, groups: SpotlightActionGroupData[]) => void;

/** Mounts one feature's palette source, with a `report` that stays the same across renders. */
function Source({ feature, Palette, clusterId, onReport }: {
  feature: FeatureId;
  Palette: PaletteSource;
  clusterId?: string;
  onReport: Report;
}) {
  const report = useCallback((groups: SpotlightActionGroupData[]) => onReport(feature, groups), [feature, onReport]);
  return <Palette clusterId={clusterId} report={report} />;
}

/**
 * ⌘K navigation across the console: jump to a view, or to whatever the features contribute — a
 * cluster, a queue by name. Mounted once in the root layout; the shortcut is registered by
 * {@link Spotlight}.
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
  const [contributed, setContributed] = useState<Partial<Record<FeatureId, SpotlightActionGroupData[]>>>({});
  const onReport = useCallback<Report>(
    (feature, groups) => setContributed((prev) => ({ ...prev, [feature]: groups })),
    [],
  );

  const groups = useMemo<SpotlightActionGroupData[]>(() => {
    const out: SpotlightActionGroupData[] = [
      {
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
        ],
      },
    ];

    // The views under the rail's own groups, so a view is found where the rail shows it.
    if (clusterId) {
      for (const navGroup of navGroups(features)) {
        out.push({
          group: navGroup.label,
          actions: navGroup.items.map((item) => ({
            id: `view-${item.path}`,
            label: item.label,
            onClick: () => navigate({ to: `/clusters/${clusterId}/${item.path}` }),
          })),
        });
      }
    }

    // In composition order, and only for features still enabled.
    for (const feature of features) out.push(...(contributed[feature.id] ?? []));
    return out;
  }, [clusterId, navigate, qc, paused, features, contributed]);

  return (
    <>
      {features.map((feature) =>
        feature.palette ? (
          <Source key={feature.id} feature={feature.id} Palette={feature.palette} clusterId={clusterId} onReport={onReport} />
        ) : null,
      )}
      <Spotlight
        actions={groups}
        shortcut={['mod + K']}
        nothingFound="Nothing matches"
        highlightQuery
        searchProps={{ placeholder: 'Jump to a cluster, view, or queue…' }}
      />
    </>
  );
}

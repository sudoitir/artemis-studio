import { useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';

import type { PaletteSource } from '../../kernel/feature.ts';

const VIEWS = [
  { view: 'connections', noun: 'connections', by: 'remote address, client id or connection id' },
  { view: 'consumers', noun: 'consumers', by: 'queue or session id' },
  { view: 'sessions', noun: 'sessions', by: 'session id, connection id or user' },
  { view: 'producers', noun: 'producers', by: 'address, name or session id' },
  { view: 'addresses', noun: 'addresses', by: 'name' },
] as const;

/**
 * The palette's live-view searches (ADR-0109). These views are read live from every broker, so the
 * palette never reads them itself — a keystroke must not be a management call per node. It offers
 * to open each view filtered to what was typed, which reads once, when the operator chooses it.
 */
export const ResourcePalette: PaletteSource = ({ clusterId, query, report }) => {
  const navigate = useNavigate();
  useEffect(() => {
    if (!clusterId || query.length < 2) {
      report([]);
      return;
    }
    report([
      {
        group: 'Search live views',
        actions: VIEWS.map(({ view, noun, by }) => ({
          id: `search-${view}`,
          label: `Search ${noun} for "${query}"`,
          description: `Matches ${by}`,
          keywords: [query],
          onClick: () => navigate({ to: `/clusters/${clusterId}/${view}`, search: { q: query } }),
        })),
      },
    ]);
  }, [clusterId, query, navigate, report]);
  return null;
};

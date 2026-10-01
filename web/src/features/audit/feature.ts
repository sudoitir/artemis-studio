import { IconClipboardList } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';

function validateAuditSearch(raw: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const k of ['user', 'action', 'outcome', 'from', 'to'] as const) {
    if (typeof raw[k] === 'string' && raw[k]) out[k] = raw[k];
  }
  const event = Number(raw.event);
  if (Number.isInteger(event) && event > 0) out.event = event;
  const parentId = Number(raw.parentId);
  if (Number.isInteger(parentId) && parentId > 0) out.parentId = parentId;
  const page = Number(raw.page);
  if (Number.isFinite(page) && page > 1) out.page = Math.floor(page);
  return out;
}

const auditRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'audit',
  component: lazyFeatureView('audit', () => import('./AuditView.tsx'), 'AuditView'),
  validateSearch: validateAuditSearch,
});

/** The audit log of every mutating call against this cluster (non-negotiable #3). */
export const auditFeature = defineFeature({
  contract: CONTRACT,
  id: 'audit',
  routes: { cluster: [auditRoute] },
  nav: [
    {
      group: 'activity',
      order: 20,
      label: 'Audit',
      icon: IconClipboardList,
      path: 'audit',
      hotkey: 'u',
      permission: 'cluster:read',
    },
  ],
});

import { IconStethoscope } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { keys } from './api.ts';
import { SetupReviewView } from './SetupReviewView.tsx';
import { SEVERITY_FILTERS, type SeverityFilter } from './words.ts';

export interface SetupReviewSearch {
  severity?: SeverityFilter;
  accepted?: 'hide';
}

const setupReviewRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'setup-review',
  component: featureView('setupreview', SetupReviewView),
  validateSearch: (raw: Record<string, unknown>): SetupReviewSearch => ({
    severity: (SEVERITY_FILTERS as readonly string[]).includes(String(raw.severity))
      ? (raw.severity as SeverityFilter)
      : undefined,
    accepted: raw.accepted === 'hide' ? 'hide' : undefined,
  }),
});

/**
 * Setup review (ADR-0106): the cluster's HA, clustering, durability and message-safety
 * configuration against a catalogue of known mistakes, each with the evidence and the fix.
 */
export const setupreviewFeature = defineFeature({
  contract: CONTRACT,
  id: 'setupreview',
  routes: { cluster: [setupReviewRoute] },
  nav: [
    {
      group: 'configuration',
      // Before Configuration (10): what is wrong comes before declaring what should be.
      order: 5,
      label: 'Setup review',
      icon: IconStethoscope,
      path: 'setup-review',
      permission: 'cluster:read',
    },
  ],
  streamTopics: {
    'setup-review': ({ clusterId, invalidate }) => invalidate(keys.review(clusterId)),
  },
});

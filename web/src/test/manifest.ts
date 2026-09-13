import { http, HttpResponse } from 'msw';

import { FEATURE_IDS } from '../kernel/feature.ts';

/**
 * A `/manifest` response for every module, with the named ones disabled (feature-modules spec).
 * A feature's title is its id, which is enough to find it by.
 *
 * Built from the ids alone, never from the feature list: the test setup installs this handler
 * before a test file's module mocks apply, and importing the features here would load their
 * components unmocked.
 */
export function manifestHandler(disabled: string[] = []) {
  return http.get('*/api/v1/manifest', () =>
    HttpResponse.json({
      contract: 1,
      features: FEATURE_IDS.map((id) => ({
        id,
        title: id,
        kind: 'FEATURE',
        enabled: !disabled.includes(id),
        enabledProperty: `artemis-studio.features.${id}.enabled`,
        permissions: [],
        topics: [],
      })),
      permissionCatalogue: [],
      identityProviders: [],
    }),
  );
}

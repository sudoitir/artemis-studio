import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { FEATURE_IDS } from '../kernel/feature.ts';
import { FEATURES } from './features.ts';

/**
 * The frontend and backend name modules the same way (ADR-0070). `web/manifest.snapshot.json` is
 * written by the backend's `ManifestSnapshotTest` from the installed modules, so a module added,
 * removed or renamed on one side fails here until the other side follows.
 */
describe('feature ids', () => {
  // Read from the web project's root, where vitest runs.
  const snapshot = JSON.parse(readFileSync('manifest.snapshot.json', 'utf8')) as {
    modules: { id: string }[];
  };

  it("match the backend's installed modules", () => {
    expect([...FEATURE_IDS].sort()).toEqual(snapshot.modules.map((module) => module.id).sort());
  });

  it('are used by one frontend feature each', () => {
    const ids = FEATURES.map((feature) => feature.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

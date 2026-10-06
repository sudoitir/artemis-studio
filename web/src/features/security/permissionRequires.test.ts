import { describe, expect, it } from 'vitest';

import type { PermissionView } from './api.ts';
import { dependentsOf, withRequired } from './permissionRequires.ts';

const perm = (action: string, requires: string[] = []): PermissionView => ({
  action,
  label: action,
  featureId: 'queues',
  featureTitle: 'Queues',
  scope: 'RESOURCE',
  resourceKinds: ['QUEUE'],
  requires,
});

const CATALOGUE = [perm('queue:read'), perm('queue:purge', ['queue:read']), perm('queue:delete', ['queue:purge'])];

describe('withRequired', () => {
  it('adds the requirements of a permission, transitively, and names who asked', () => {
    const { next, added } = withRequired(CATALOGUE, [], ['queue:delete']);

    expect(next).toEqual(['queue:delete', 'queue:purge', 'queue:read']);
    expect(added).toEqual([
      { permission: 'queue:purge', requiredBy: 'queue:delete' },
      { permission: 'queue:read', requiredBy: 'queue:purge' },
    ]);
  });

  it('adds nothing that is already held, directly or through a wildcard', () => {
    expect(withRequired(CATALOGUE, ['queue:read'], ['queue:purge']).added).toEqual([]);
    expect(withRequired(CATALOGUE, ['*'], ['queue:purge']).added).toEqual([]);
    expect(withRequired(CATALOGUE, ['queue:*'], ['queue:purge']).added).toEqual([]);
  });

  it('does not report a requirement that was chosen in the same step', () => {
    expect(withRequired(CATALOGUE, [], ['queue:purge', 'queue:read']).added).toEqual([]);
  });
});

describe('dependentsOf', () => {
  it('finds what needs a removed permission, following the chain', () => {
    const held = ['queue:read', 'queue:purge', 'queue:delete'];

    expect(dependentsOf(CATALOGUE, held, ['queue:read'])).toEqual([
      { permission: 'queue:purge', needs: 'queue:read' },
      { permission: 'queue:delete', needs: 'queue:purge' },
    ]);
  });

  it('finds nothing when no held permission needs it, or a wildcard still gives it', () => {
    expect(dependentsOf(CATALOGUE, ['queue:read', 'queue:purge'], ['queue:purge'])).toEqual([]);
    expect(dependentsOf(CATALOGUE, ['queue:*', 'queue:read', 'queue:purge'], ['queue:read'])).toEqual([]);
  });
});

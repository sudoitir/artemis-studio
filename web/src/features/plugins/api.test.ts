import { describe, expect, it } from 'vitest';

import { ApiError } from '../../kernel/api/request.ts';
import { pollInterval, type PluginsView } from './api.ts';

const quiet = { restart: { restarting: false }, plugins: [{ status: 'active' }] } as unknown as PluginsView;
const restarting = { restart: { restarting: true }, plugins: [] } as unknown as PluginsView;
const activating = { restart: { restarting: false }, plugins: [{ status: 'activating' }] } as unknown as PluginsView;

const failed = (status: number) => new ApiError(status, {});

describe('the plugins inventory poll', () => {
  it('asks every 30 seconds while nothing changes', () => {
    expect(pollInterval({ data: quiet, error: null, fetchFailureCount: 0 })).toBe(30_000);
  });

  it('asks every second while a plugin is activating or Studio restarts, even when polls fail', () => {
    expect(pollInterval({ data: activating, error: null, fetchFailureCount: 0 })).toBe(1_000);
    expect(pollInterval({ data: restarting, error: failed(503), fetchFailureCount: 6 })).toBe(1_000);
  });

  it('stops asking once the permission is refused', () => {
    expect(pollInterval({ data: undefined, error: failed(403), fetchFailureCount: 1 })).toBe(false);
  });

  it('backs off from one second to the quiet interval while a server keeps failing', () => {
    const at = (fetchFailureCount: number) => pollInterval({ data: undefined, error: failed(500), fetchFailureCount });
    expect([at(0), at(1), at(2), at(3)]).toEqual([1_000, 2_000, 4_000, 8_000]);
    expect(at(5)).toBe(30_000);
    expect(at(40)).toBe(30_000);
  });
});

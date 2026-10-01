import { absoluteLabel, elapsedLabel } from '../../kernel/time/time.ts';
import type { JobHealth, NodeHealth, ReplicaHealth } from './api.ts';

export const JOB_STATES: Record<JobHealth['status'], string> = {
  OK: 'Last run succeeded',
  FAILING: 'Last run failed',
  NEVER_RUN: 'Not run yet',
};

export const REPLICA_STATES: Record<ReplicaHealth['state'], { label: string; detail?: string }> = {
  STARTING: { label: 'Starting' },
  READY: { label: 'Ready' },
  DRAINING: { label: 'Draining', detail: 'Shutting down; takes no new requests' },
  STOPPED: { label: 'Stopped' },
  GONE: { label: 'Gone', detail: 'No heartbeat; presumed crashed' },
};

/** A figure the server could not read is said to be unavailable, never shown as zero. */
export function figureText(value: number | null | undefined, unit?: string): string {
  if (value === null || value === undefined) return 'Unavailable';
  const suffix = unit ? ` ${unit}` : '';
  return `${value.toLocaleString()}${suffix}`;
}

export function momentText(at: string | null | undefined): string {
  return at ? absoluteLabel(at) : 'Never';
}

export function lagText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return 'Unavailable';
  return seconds === 0 ? 'On schedule' : `${elapsedLabel(seconds * 1000)} behind`;
}

export function replicaHealthText(r: ReplicaHealth): string {
  if (r.state === 'STOPPED') return 'Not running';
  return r.degraded ? 'Degraded' : 'Healthy';
}

/** When a node last failed, with the error it gave, if it gave one. */
export function failureText(n: NodeHealth): string {
  const error = n.lastError ? ` ${n.lastError}` : '';
  return `${momentText(n.lastFailure)}${error}`;
}

export const heartbeatText = (r: ReplicaHealth) => `${elapsedLabel(r.heartbeatAgeMillis)} ago`;

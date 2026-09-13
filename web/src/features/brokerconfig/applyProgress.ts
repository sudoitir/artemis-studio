import { useSyncExternalStore } from 'react';

import { keys } from './api.ts';
import type { TopicHandler } from '../../kernel/feature.ts';

/** One node's position in a running apply, as the server last reported it. */
export type ApplyProgress = {
  applyId: number;
  nodeId: string;
  nodeName: string;
  canary: boolean;
  phase: 'APPLYING' | 'VERIFYING' | 'DONE' | 'HALTED' | 'UNREACHABLE';
  done: number;
  total: number;
};

/**
 * Apply progress as a store rather than a query.
 *
 * The frames arrive on the `config` topic while the apply's own POST is still in
 * flight, so there is no server-side resource to invalidate and nothing to
 * refetch — the response is the authoritative answer and lands when it lands.
 * Keyed by node, last frame wins; the apply screen clears it when it starts a
 * run so a previous apply's tail cannot be read as this one's progress.
 */
let progress = new Map<string, ApplyProgress>();
const listeners = new Set<() => void>();

function publish(frame: ApplyProgress) {
  progress = new Map(progress).set(frame.nodeId, frame);
  for (const listener of listeners) listener();
}

export function clearApplyProgress() {
  if (progress.size === 0) return;
  progress = new Map();
  for (const listener of listeners) listener();
}

/**
 * Every node's latest frame, in the order the nodes were first heard from —
 * which is the order the apply walks them, canary first.
 *
 * Not filtered by apply id, because the caller cannot know the id until the POST
 * it is waiting on returns. Clearing at the start of a run is what scopes it, and
 * one apply per cluster at a time (ADR-0067 D12) is what makes that sound.
 */
export function useApplyProgress(): ApplyProgress[] {
  const all = useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    () => progress,
    () => progress,
  );
  return [...all.values()];
}

/**
 * The `config` topic. Two kinds of frame share it. A progress frame is a running
 * apply reporting where it has got to: it has no resource behind it, and
 * invalidating on every step would refetch the declaration dozens of times during
 * one apply. Anything else is the ordinary signal that a drift evaluation or an
 * apply finished; the declaration view carries both, so one key covers its tabs.
 */
export const configTopic: TopicHandler = ({ clusterId, data, invalidate }) => {
  let frame: Partial<ApplyProgress> & { kind?: string } = {};
  try {
    frame = JSON.parse(data);
  } catch {
    /* malformed frame — treat as a plain signal */
  }
  if (frame.kind === 'apply-progress' && frame.nodeId) {
    publish(frame as ApplyProgress);
    return;
  }
  invalidate(keys.brokerConfig(clusterId));
};

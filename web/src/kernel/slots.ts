import type { ComponentType } from 'react';

import { useFeatures } from './features.ts';

/**
 * The kernel-owned slots and what each hands its contributions (ADR-0070). A slot lets a screen
 * show another feature's panel without importing that feature, and without a placeholder when the
 * feature is disabled.
 */
export interface SlotProps {
  /** Below a passing registration check: what the enabled features added to it, by feature id. */
  'cluster.registration.afterProbe': { contributions: Record<string, unknown> };
  /** In a queue's detail drawer, below its per-node breakdown. `onClose` closes the drawer before navigating. */
  'queue.detail.panels': { clusterId: string; queueName: string; onClose: () => void };
  /** At the foot of a cluster's metrics view. */
  'metrics.panels': { clusterId: string };
}

export type SlotName = keyof SlotProps;

export interface SlotContribution<P> {
  /** Unique within the slot. */
  id: string;
  /** Position within the slot; lower comes first. */
  order: number;
  Component: ComponentType<P>;
}

export type SlotContributions = { [K in SlotName]?: SlotContribution<SlotProps[K]>[] };

/** The enabled features' contributions to one slot, in order. */
export function useSlot<K extends SlotName>(name: K): SlotContribution<SlotProps[K]>[] {
  return useFeatures()
    .flatMap((feature) => feature.slots?.[name] ?? [])
    .sort((a, b) => a.order - b.order);
}

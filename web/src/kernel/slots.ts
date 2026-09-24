import type { ComponentType } from 'react';

import type {
  ActionProps,
  ActionSection,
  AddressTarget,
  ClientTarget,
  ConnectionTarget,
  ConsumerTarget,
  DivertTarget,
  LinkProps,
  MessageTarget,
  ProducerTarget,
  QueueTarget,
  SessionTarget,
} from './actions/types.ts';
import { useFeatures } from './features.ts';

/** Queues picked by name, or every queue matching the queues screen's filter (`q`, blank for all). */
export type QueueSelection = { kind: 'names'; names: string[] } | { kind: 'filter'; q: string; total: number };

/** Messages picked by id, every message matching the messages screen's selector, or the whole queue. */
export type MessageSelection = { kind: 'ids'; ids: number[] } | { kind: 'filter'; filter: string } | { kind: 'all' };

/**
 * The kernel-owned slots and what each hands its contributions (ADR-0070). A slot lets a screen
 * show another feature's panel without importing that feature, and without a placeholder when the
 * feature is disabled.
 */
export interface SlotProps {
  /** In the application header, after the product name: status across clusters that needs attention. */
  'shell.header': object;
  /** In the sidebar, above the open cluster's views: how the operator moves between clusters. */
  'shell.navbar': { collapsed: boolean };
  /** The landing page, when no cluster is open. */
  'home.empty': object;
  /** Above every view of a cluster: what identifies it, and what needs saying about its state. */
  'cluster.header': { clusterId: string };
  /** Below a passing registration check: what the enabled features added to it, by feature id. */
  'cluster.registration.afterProbe': { contributions: Record<string, unknown> };
  /** In a queue's detail drawer, below its per-node breakdown. `onClose` closes the drawer before navigating. */
  'queue.detail.panels': { clusterId: string; queueName: string; onClose: () => void };
  /**
   * Beside the queues screen's selection: what can be done to the selected queues. `count` is how
   * many are selected, and may be zero: an action then stays visible and disabled. `clear` empties
   * the selection.
   */
  'queues.selection': { clusterId: string; selection: QueueSelection; count: number; clear: () => void };
  /**
   * Beside the messages screen's selection: what can be done to the selected messages elsewhere.
   * `node` is the Studio node being browsed, absent for the live node; `total` is how many messages
   * the selection holds, null when the screen does not know. `clear` empties the selection.
   */
  'messages.selection': {
    clusterId: string;
    queueName: string;
    node?: string;
    selection: MessageSelection;
    total: number | null;
    clear: () => void;
  };
  /** At the foot of a cluster's metrics view. */
  'metrics.panels': { clusterId: string };
  /** Inside a box on the topology graph, after its name. `nodeIds` are the broker endpoints the box stands for. */
  'topology.node.marks': { clusterId: string; nodeIds: string[] };
  /** A section of a cluster's Settings page, under the contribution's title. */
  'settings.sections': { clusterId: string };
  /** A tab of a cluster's Routing page, after Diverts and Bridges, labelled with the contribution's title; its id is the tab's `?tab=`. */
  'routing.tabs': { clusterId: string };
  /** A tab of the Administration page, labelled with the contribution's title; its id is the tab's `?tab=`. */
  'admin.tabs': object;
  /** A section of the signed-in user's Account page, under the contribution's title. */
  'account.sections': object;

  /*
   * Row actions (ADR-0105): the items of a resource's row menu, wherever a grid lists it. Each
   * contribution names its `section`, renders `ActionMenuItem`s, and opens its dialogs through
   * `host`, never in the row. In `navigate` mode it offers nothing that changes the broker.
   */
  'queue.actions': ActionProps<QueueTarget>;
  'address.actions': ActionProps<AddressTarget>;
  'connection.actions': ActionProps<ConnectionTarget>;
  'session.actions': ActionProps<SessionTarget>;
  'consumer.actions': ActionProps<ConsumerTarget>;
  'producer.actions': ActionProps<ProducerTarget>;
  'message.actions': ActionProps<MessageTarget>;
  'divert.actions': ActionProps<DivertTarget>;
  'client.actions': ActionProps<ClientTarget>;

  /*
   * Links (ADR-0105): the owning feature's link to one resource, wrapping its name. Built-in only —
   * a plugin may not decide where Studio's own resource links lead.
   */
  'queue.link': LinkProps<QueueTarget>;
  'address.link': LinkProps<AddressTarget>;
  'connection.link': LinkProps<ConnectionTarget>;
  'session.link': LinkProps<SessionTarget>;
}

export type SlotName = keyof SlotProps;

/**
 * The closed, ordered headings a Settings page groups its tabs under, in the order an operator's
 * reach widens: their own preferences, then what is shared across Studio, then this cluster, then
 * what plugins added. Adding one is a kernel change, like a navigation group (ADR-0070).
 */
export const SETTINGS_GROUPS = [
  { id: 'personal', label: 'Yours' },
  { id: 'studio', label: 'Studio' },
  { id: 'cluster', label: 'This cluster' },
  { id: 'plugins', label: 'Plugins' },
] as const;

export type SettingsGroupId = (typeof SETTINGS_GROUPS)[number]['id'];

export interface SlotContribution<P> {
  /** Unique within the slot. */
  id: string;
  /** Position within the slot; lower comes first. */
  order: number;
  /** The heading or tab label, in the slots that show one. */
  title?: string;
  /** `settings.sections` only: the heading its tab sits under. Without one it is listed under Plugins. */
  group?: SettingsGroupId;
  /** `*.actions` only: the menu section the item is listed under. */
  section?: ActionSection;
  Component: ComponentType<P>;
}

export type SlotContributions = { [K in SlotName]?: SlotContribution<SlotProps[K]>[] };

/** The enabled features' contributions to one slot, in order. */
export function useSlot<K extends SlotName>(name: K): SlotContribution<SlotProps[K]>[] {
  return useFeatures()
    .flatMap((feature) => feature.slots?.[name] ?? [])
    .sort((a, b) => a.order - b.order);
}

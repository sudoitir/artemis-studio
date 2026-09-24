import type { ComponentType, ReactNode } from 'react';

import { useSlot, type SlotName } from '../slots.ts';
import type { ActionTargets } from './types.ts';

/** The kinds another view can link to, each through the owning feature's `<kind>.link` slot. */
export type LinkKind = 'queue' | 'address' | 'connection' | 'session';

type LinkSlot<K extends LinkKind> = `${K}.link` & SlotName;

/**
 * A resource's name, as a link to the resource itself (ADR-0105): the owning feature renders the
 * link, so a view naming a queue never needs to know where queues live. When that feature is
 * disabled the name is plain text — never a link that leads to a page explaining it is off.
 */
export function ResourceLink<K extends LinkKind>({
  kind,
  clusterId,
  target,
  children,
}: {
  kind: K;
  clusterId: string;
  target: ActionTargets[K];
  children: ReactNode;
}) {
  const [first] = useSlot(`${kind}.link` as LinkSlot<K>);
  if (!first) return <>{children}</>;
  const Link = first.Component as ComponentType<object>;
  const props: object = { clusterId, target, children };
  return <Link {...props} />;
}

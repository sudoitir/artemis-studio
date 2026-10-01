import { ResourceLink } from '../../kernel/actions/ResourceLink.tsx';
import type { ConsumerHealthView } from './api.ts';

/** The queue's name, as a link to it (ADR-0107) where a feature offers one. */
export function QueueLink({ row }: Readonly<{ row: ConsumerHealthView }>) {
  return (
    <ResourceLink kind="queue" target={{ queueName: row.queueName, address: row.address }}>
      {row.queueName}
    </ResourceLink>
  );
}

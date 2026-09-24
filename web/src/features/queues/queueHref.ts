import { clusterHref } from '../../kernel/routing/href.ts';

/** Where a queue's detail opens: the Queues view with its drawer, whatever page it is on. */
export function queueHref(clusterId: string, queueName: string): string {
  return clusterHref(clusterId, 'queues', { queue: queueName });
}

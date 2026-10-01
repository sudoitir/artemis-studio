import { Stack, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';

import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { FlowBrokerNodeView } from './api.ts';

const TITLES: Record<string, string> = {
  UNREACHABLE: 'did not answer',
  PERMISSION_DENIED: 'refused to list clients',
  COUNTER_UNAVAILABLE: 'reports no rate counters',
  ROUTING_UNAVAILABLE: 'could not read its routing',
  FAILED: 'sampling failed',
};

/** A node that has no counters or routing to read is a fact about it; one that failed to answer is a fault. */
const FACTS = new Set(['COUNTER_UNAVAILABLE', 'ROUTING_UNAVAILABLE']);

/**
 * Per-node reasons data is missing, stated where the data would be (spec: unreachable is not empty).
 * A truncated sample says how much of the node was read, so a partial answer never reads as whole.
 */
export function BrokerNodeNotices({ nodes }: Readonly<{ nodes: FlowBrokerNodeView[] }>) {
  const troubled = nodes.filter((n) => n.state !== 'OK');
  const truncated = nodes.filter((n) => n.state === 'OK' && n.truncated);
  if (troubled.length === 0 && truncated.length === 0) return null;

  return (
    <Stack gap="md">
      {troubled.map((node) => (
        <Section
          key={node.nodeId}
          variant="card"
          title={`${node.name ?? 'A node'} ${TITLES[node.state ?? 'FAILED'] ?? TITLES.FAILED}`}
          description={node.message}
          actions={FACTS.has(node.state ?? '') ? null : <StatusBadge tone="warning">Not sampled</StatusBadge>}
        >
          {node.brokerXmlSnippet ? <CodeHighlight code={node.brokerXmlSnippet} language="xml" /> : null}
        </Section>
      ))}
      {truncated.map((node) => (
        <Section key={node.nodeId} variant="card" title={`${node.name ?? 'A node'} was partly sampled`}>
          <Text size="sm">
            Read {node.producersSeen} of {node.producersTotal} producers and {node.consumersSeen} of{' '}
            {node.consumersTotal} consumers. Rates cover the sampled clients only. Raise “Rows read per node” in
            Settings to sample more of this node.
          </Text>
        </Section>
      ))}
    </Stack>
  );
}

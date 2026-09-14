import { Alert, Stack, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';

import type { FlowBrokerNodeView } from './api.ts';

const TITLES: Record<string, string> = {
  UNREACHABLE: 'did not answer',
  PERMISSION_DENIED: 'refused to list clients',
  COUNTER_UNAVAILABLE: 'reports no rate counters',
  FAILED: 'sampling failed',
};

/**
 * Per-node reasons data is missing, stated where the data would be (spec: unreachable is not empty).
 * A truncated sample says how much of the node was read, so a partial answer never reads as whole.
 */
export function BrokerNodeNotices({ nodes }: { nodes: FlowBrokerNodeView[] }) {
  const troubled = nodes.filter((n) => n.state !== 'OK');
  const truncated = nodes.filter((n) => n.state === 'OK' && n.truncated);
  if (troubled.length === 0 && truncated.length === 0) return null;

  return (
    <Stack gap="xs">
      {troubled.map((node) => (
        <Alert
          key={node.nodeId}
          variant="light"
          color={node.state === 'COUNTER_UNAVAILABLE' ? 'gray' : 'yellow'}
          title={`${node.name ?? 'A node'} ${TITLES[node.state ?? 'FAILED'] ?? TITLES.FAILED}`}
        >
          <Stack gap="xs">
            <Text size="sm">{node.message}</Text>
            {node.brokerXmlSnippet ? <CodeHighlight code={node.brokerXmlSnippet} language="xml" /> : null}
          </Stack>
        </Alert>
      ))}
      {truncated.map((node) => (
        <Alert key={node.nodeId} variant="light" color="gray" title={`${node.name ?? 'A node'} was partly sampled`}>
          <Text size="sm">
            Read {node.producersSeen} of {node.producersTotal} producers and {node.consumersSeen} of{' '}
            {node.consumersTotal} consumers. Rates cover the sampled clients only. Raise “Rows read per node” in
            Settings to sample more of this node.
          </Text>
        </Alert>
      ))}
    </Stack>
  );
}

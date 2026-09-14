import { useEffect, useRef } from 'react';
import { Button, CloseButton, Group, Stack, Text, Title } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import type { FlowEdgeView, FlowGraphView } from './api.ts';
import { edgeText, FAULT_LABELS, formatCount, rateSourceLabel } from './flowFormat.ts';
import { focusOf } from './flowSearch.ts';
import classes from './FlowView.module.css';

const KIND_WORD: Record<string, string> = {
  PRODUCER: 'Producing client',
  CONSUMER: 'Consuming client',
  ADDRESS: 'Address',
  QUEUE: 'Queue',
};

/**
 * One node, explained (flow-visualization spec: the inspector explains a resource and links to
 * existing actions without mutating). Closing it returns focus to whatever opened it — the caller
 * owns that, because only it knows what opened it.
 */
export function FlowInspector({
  graph,
  nodeId,
  clusterId,
  onClose,
  onFocus,
}: {
  graph: FlowGraphView;
  nodeId: string;
  clusterId: string;
  onClose: () => void;
  onFocus: (focus: string) => void;
}) {
  const navigate = useNavigate();
  const now = useServerNow(5_000);
  const close = useRef<HTMLButtonElement>(null);
  const nodes = new Map((graph.nodes ?? []).map((n) => [n.id, n]));
  const node = nodes.get(nodeId);

  useEffect(() => {
    close.current?.focus();
  }, [nodeId]);

  if (!node) return null;

  const inbound = (graph.edges ?? []).filter((e) => e.target === nodeId);
  const outbound = (graph.edges ?? []).filter((e) => e.source === nodeId);
  const faults = (node.faults ?? []).map((f) => FAULT_LABELS[f] ?? f.toLowerCase());
  const open = (path: string) =>
    navigate({ to: `/clusters/$clusterId/${path}`, params: { clusterId }, search: { q: node.label } });

  const facts: Array<[string, string]> = [];
  if (node.kind === 'QUEUE') {
    facts.push([
      'Backlog',
      node.messageCount === null || node.messageCount === undefined
        ? 'not swept yet'
        : `${formatCount(node.messageCount)} waiting`,
    ]);
    facts.push(['Consumers', node.consumerCount === null || node.consumerCount === undefined ? 'not swept yet' : String(node.consumerCount)]);
  }
  if (node.kind === 'ADDRESS' && node.routingTypes?.length) facts.push(['Routing', node.routingTypes.join(', ').toLowerCase()]);
  if (node.members) facts.push(['Connections', String(node.members)]);
  if (node.protocols?.length) facts.push(['Protocols', node.protocols.join(', ')]);
  if (node.hosts?.length) facts.push(['Hosts', node.hosts.join(', ')]);
  if (node.users?.length) facts.push(['Users', node.users.join(', ')]);
  if (node.brokerNodes?.length) facts.push(['Seen on', node.brokerNodes.join(', ')]);

  const flowList = (title: string, edges: FlowEdgeView[], other: (e: FlowEdgeView) => string | undefined) =>
    edges.length === 0 ? null : (
      <Stack gap={4}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          {title}
        </Text>
        {edges.map((e) => (
          <div key={e.id} className={classes.flowRow}>
            <Text size="sm" truncate title={nodes.get(other(e))?.label}>
              {nodes.get(other(e))?.label ?? '—'}
            </Text>
            <Text size="sm" className={e.faults?.length ? classes.alarm : classes.figure}>
              {edgeText(e)}
            </Text>
            <Text size="xs" c="dimmed">
              {rateSourceLabel(e)}
              {e.asOf ? ` · ${elapsedLabel(now - Date.parse(e.asOf))} ago` : ''}
            </Text>
          </div>
        ))}
      </Stack>
    );

  return (
    <aside
      className={classes.inspector}
      aria-label={`Details of ${KIND_WORD[node.kind ?? ''] ?? 'node'} ${node.label}`}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation();
          onClose();
        }
      }}
    >
      <Stack gap="md">
        <Group justify="space-between" align="flex-start" wrap="nowrap">
          <div className={classes.inspectorTitle}>
            <Text size="xs" c="dimmed">
              {KIND_WORD[node.kind ?? '']}
            </Text>
            <Title order={4} className={classes.breakAnywhere}>
              {node.label}
            </Title>
          </div>
          <CloseButton ref={close} aria-label="Close details" onClick={onClose} />
        </Group>

        {faults.length ? (
          <Text size="sm" fw={600} className={classes.alarm}>
            {faults.join(', ')}
          </Text>
        ) : null}

        {facts.length ? (
          <dl className={classes.facts}>
            {facts.map(([term, value]) => (
              <div key={term} className={classes.fact}>
                <dt>{term}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        ) : null}

        {flowList('Flow in', inbound, (e) => e.source)}
        {flowList('Flow out', outbound, (e) => e.target)}

        <Stack gap="xs">
          <Button size="xs" variant="default" onClick={() => onFocus(focusOf(node))}>
            Focus the view on this
          </Button>
          {node.kind === 'QUEUE' || node.kind === 'ADDRESS' ? (
            <Button size="xs" variant="subtle" onClick={() => open('queues')}>
              Open in Queues
            </Button>
          ) : null}
          {node.kind === 'CONSUMER' ? (
            <Button size="xs" variant="subtle" onClick={() => open('consumers')}>
              Open its consumers
            </Button>
          ) : null}
          {node.kind === 'PRODUCER' ? (
            <Button size="xs" variant="subtle" onClick={() => open('producers')}>
              Open its producers
            </Button>
          ) : null}
          {node.kind === 'PRODUCER' || node.kind === 'CONSUMER' ? (
            <Button size="xs" variant="subtle" onClick={() => open('connections')}>
              Open its connections
            </Button>
          ) : null}
          <Text size="xs" c="dimmed">
            Flow never changes the broker. Closing a connection or consumer happens on its own screen, with its
            confirmation.
          </Text>
        </Stack>
      </Stack>
    </aside>
  );
}

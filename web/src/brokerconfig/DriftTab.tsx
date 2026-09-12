import { Alert, Anchor, Button, Group, Stack, Table, Text, Tooltip } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import {
  useEvaluateBrokerConfigDrift,
  type ConfigCatalogueView,
  type ConfigDeclarationView,
  type ConfigNodeStateView,
} from '../api/client.ts';
import { absoluteLabel, elapsedLabel, serverNow, useServerNow } from '../app/time.ts';
import { useDisplayZone } from '../app/timezone.ts';
import classes from './Configuration.module.css';
import { findingKindWords, findingRows, nodeStateWords, wireSectionLabel } from './words.ts';

const KIND_ORDER = ['MISSING', 'DIVERGENT', 'DIVERGENT_QUEUE', 'DIVERGENT_ADDRESS', 'UNDECLARED', 'UNVERIFIABLE', 'NOT_EVALUATED'];

/**
 * How long ago, on Studio's clock, never the browser's (`app/time.ts`). Relative
 * because the question an operator asks of this screen is "is this current?", and
 * a wall-clock stamp only answers it after arithmetic they have to do themselves.
 * The absolute instant stays one hover or one focus away.
 */
function ago(at: string): string {
  return `${elapsedLabel(serverNow() - Date.parse(at))} ago`;
}

/** The scheduled cadence in the same shape as the age above it, so the two compare by eye. */
function cadence(seconds: number): string {
  return `evaluated about every ${elapsedLabel(seconds * 1_000)}`;
}

/**
 * Every live node measured against the declaration. The resolved state comes
 * first — "all N live nodes match revision R" is a sentence, not an empty table
 * — and findings are grouped by kind with the declared value beside the
 * observed one, node named on every row.
 */
export function DriftTab({
  declaration,
  canEvaluate,
  catalogue,
}: {
  declaration: ConfigDeclarationView;
  canEvaluate: boolean;
  catalogue?: ConfigCatalogueView;
}) {
  useDisplayZone();
  useServerNow();
  const evaluate = useEvaluateBrokerConfigDrift(declaration.clusterId);
  const live = declaration.nodes.filter((n) => n.live);
  const drifted = live.filter((n) => n.state === 'DRIFTED');
  const unreachable = live.filter((n) => n.state === 'UNREACHABLE');
  const notEvaluated = live.filter((n) => n.state === 'NOT_EVALUATED');
  const inSync = live.filter((n) => n.state === 'IN_SYNC');
  const latest = declaration.nodes.map((n) => n.evaluatedAt).filter((t): t is string => !!t).sort().at(-1);

  const summary = (() => {
    if (live.length === 0) return 'No node is live, so there is nothing to compare the declaration with.';
    if (inSync.length === live.length) return `All ${live.length} live node${live.length === 1 ? '' : 's'} match revision ${declaration.revision}.`;
    const parts: string[] = [];
    if (drifted.length) parts.push(`${drifted.length} drifted`);
    if (inSync.length) parts.push(`${inSync.length} in sync`);
    if (unreachable.length) parts.push(`${unreachable.length} unreachable — not evaluated`);
    if (notEvaluated.length) parts.push(`${notEvaluated.length} not evaluated yet`);
    return `${live.length} live node${live.length === 1 ? '' : 's'}: ${parts.join(', ')}.`;
  })();
  const tone = drifted.length > 0 ? 'warning' : undefined;

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-start">
        <Stack gap={2}>
          <Text size="sm" fw={600} className={classes.state} data-tone={tone} aria-live="polite">
            {summary}
          </Text>
          <Text size="xs" c="dimmed">
            {latest ? (
              <Tooltip label={absoluteLabel(latest)} withArrow>
                <span tabIndex={0}>Last evaluated {ago(latest)}</span>
              </Tooltip>
            ) : (
              'Not evaluated yet'
            )}
            {' · '}
            {cadence(declaration.driftIntervalSeconds)}. Evaluation runs on a schedule and after every apply; nothing
            is ever changed by it.
          </Text>
        </Stack>
        <Button
          variant="default"
          size="xs"
          loading={evaluate.isPending}
          onClick={() => evaluate.mutate()}
          disabled={!canEvaluate}
          title={canEvaluate ? undefined : 'Needs read access to this cluster.'}
        >
          Evaluate now
        </Button>
      </Group>

      {evaluate.isError ? (
        <Alert color="red" variant="light" title={evaluate.error.title} role="alert">
          {evaluate.error.message}
        </Alert>
      ) : null}

      {!declaration.reportUndeclared ? (
        <Text size="xs" c="dimmed">
          Undeclared reporting is off for this cluster: settings and diverts the declaration does not mention are not
          listed. Turn it on from the mode control in the header.
        </Text>
      ) : null}

      {declaration.nodes.map((node) => (
        <NodeFindings key={node.nodeId} node={node} clusterId={declaration.clusterId} catalogue={catalogue} />
      ))}

      <Text size="xs" c="dimmed">
        <Anchor component={Link} to={`/clusters/${declaration.clusterId}/config-diff`} size="xs">
          Config diff
        </Anchor>{' '}
        compares two nodes with each other; this compares every node with the declaration.
      </Text>
    </Stack>
  );
}

/**
 * Why an agreeing node agrees. "In sync" after an adoption and "in sync" after a
 * verified apply look identical and mean opposite things — one says Studio wrote
 * the values and read them back, the other says the declaration was copied from
 * whatever the broker happened to be doing. A node that agrees for no recorded
 * reason says that too, rather than implying the stronger one.
 */
function SyncEvidence({ node, clusterId }: { node: ConfigNodeStateView; clusterId: string }) {
  if (node.state !== 'IN_SYNC') return null;
  if (!node.basis) {
    return (
      <Text size="xs" c="dimmed">
        No record of why it agrees.
      </Text>
    );
  }
  const words = {
    VERIFIED_APPLY: node.basisRef ? `Verified by apply #${node.basisRef}` : 'Verified by an apply',
    ADOPTED: node.basisRef ? `Adopted as revision ${node.basisRef}; no broker was written` : 'Adopted from this cluster',
    OBSERVED_MATCH: 'Observed to match; Studio has not written to this node',
  }[node.basis];
  return (
    <Anchor component={Link} to={`/clusters/${clusterId}/configuration?tab=history`} size="xs">
      {words}
    </Anchor>
  );
}

function NodeFindings({
  node,
  clusterId,
  catalogue,
}: {
  node: ConfigNodeStateView;
  clusterId: string;
  catalogue?: ConfigCatalogueView;
}) {
  const state = nodeStateWords(node.state);
  const groups = new Map<string, typeof node.findings>();
  for (const f of node.findings) {
    const list = groups.get(f.kind) ?? [];
    list.push(f);
    groups.set(f.kind, list);
  }
  const kinds = [...groups.keys()].sort((a, b) => KIND_ORDER.indexOf(a) - KIND_ORDER.indexOf(b));

  return (
    <Stack gap="xs">
      <Group gap="sm" align="baseline">
        <Text size="sm" fw={600}>
          {node.nodeName}
        </Text>
        <Text size="xs" className={classes.chip} data-tone={state.tone}>
          {node.live ? state.text : 'not live — backups inherit through replication and are not evaluated'}
        </Text>
        {node.live && node.evaluatedAt ? (
          <Tooltip label={absoluteLabel(node.evaluatedAt)} withArrow>
            <Text size="xs" c="dimmed" tabIndex={0}>
              {ago(node.evaluatedAt)}
            </Text>
          </Tooltip>
        ) : null}
        {node.detail ? (
          <Text size="xs" c="dimmed">
            {node.detail}
          </Text>
        ) : null}
        <SyncEvidence node={node} clusterId={clusterId} />
      </Group>
      {kinds.map((kind) => (
        <Table key={kind} fz="xs" verticalSpacing={4} withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th style={{ width: 160 }}>{findingKindWords(kind)}</Table.Th>
              <Table.Th>Item</Table.Th>
              <Table.Th>Declared</Table.Th>
              <Table.Th>Observed on {node.nodeName}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {groups.get(kind)!.map((f, i) => {
              // The keys that differ come first and are the only ones in full weight;
              // the ones that agree are there for context, dimmed, never hidden.
              const rows = findingRows(f, catalogue);
              const ordered = [...rows.filter((r) => r.differs), ...rows.filter((r) => !r.differs)];
              const cell = (side: 'declared' | 'observed') =>
                ordered.length === 0
                  ? '—'
                  : ordered.map((r) => (
                      <div key={r.key} className={classes.kvRow} data-differs={r.differs || undefined}>
                        <span className={classes.kvKey}>{r.key}</span>
                        <span className={classes.kvValue}>{r[side]}</span>
                      </div>
                    ));
              return (
                <Table.Tr key={`${f.section}:${f.key}:${i}`}>
                  <Table.Td>{wireSectionLabel(f.section)}</Table.Td>
                  <Table.Td>
                    <Text size="xs">{f.key ?? '—'}</Text>
                    <Text size="xs" c="dimmed">
                      {f.detail}
                    </Text>
                  </Table.Td>
                  <Table.Td className={classes.compare}>
                    <div className={classes.kv} data-diff>
                      {cell('declared')}
                    </div>
                  </Table.Td>
                  <Table.Td className={classes.compare}>
                    <div className={classes.kv} data-diff>
                      {cell('observed')}
                    </div>
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      ))}
    </Stack>
  );
}

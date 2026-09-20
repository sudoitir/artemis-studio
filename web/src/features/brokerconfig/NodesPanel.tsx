import { Anchor, Stack, Table, Text, Tooltip } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import type { ConfigCatalogueView, ConfigDeclarationView, ConfigNodeStateView } from './api.ts';
import { absoluteLabel, elapsedLabel, serverNow, useServerNow } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import classes from './Configuration.module.css';
import { findingKindWords, findingRows, nodeStateWords, wireSectionLabel, WIRE_SECTIONS } from './words.ts';

const KIND_ORDER = ['UNDECLARED', 'DIVERGENT_QUEUE', 'DIVERGENT_ADDRESS', 'UNVERIFIABLE', 'NOT_EVALUATED'];

function ago(at: string): string {
  return `${elapsedLabel(serverNow() - Date.parse(at))} ago`;
}

/** Every key a declared row already carries, so the same finding is not told twice. */
function declaredKeys(declaration: ConfigDeclarationView): Set<string> {
  const doc = declaration.document;
  const keys = new Set<string>();
  const add = (section: keyof typeof WIRE_SECTIONS, key: string) =>
    WIRE_SECTIONS[section].forEach((wire) => keys.add(`${wire}:${key}`));
  doc.addresses.forEach((a) => {
    add('addresses', a.name);
    a.queues.forEach((q) => add('addresses', q.name));
  });
  doc.addressSettings.forEach((s) => add('addressSettings', s.match));
  doc.securitySettings.forEach((s) => add('securitySettings', s.match));
  doc.diverts.forEach((d) => add('diverts', d.name));
  return keys;
}

/**
 * The nodes themselves (ADR-0087 D1): each one's state, why an agreeing node
 * agrees, and the findings no declared row can carry — a resource the declaration
 * does not mention, a node that could not be read, one not evaluated yet.
 *
 * <p>An item's own drift is on the item's row; repeating it here would be the
 * split this screen exists to remove.
 */
export function NodesPanel({
  declaration,
  catalogue,
}: {
  declaration: ConfigDeclarationView;
  catalogue?: ConfigCatalogueView;
}) {
  useDisplayZone();
  useServerNow();
  const onRows = declaredKeys(declaration);

  return (
    <Stack gap="md">
      <Text size="sm" fw={600}>
        Nodes
      </Text>
      {declaration.nodes.map((node) => (
        <NodeCard
          key={node.nodeId}
          node={node}
          clusterId={declaration.clusterId}
          catalogue={catalogue}
          onRows={onRows}
        />
      ))}
      {!declaration.reportUndeclared ? (
        <Text size="xs" c="dimmed">
          Undeclared reporting is off for this cluster: settings and diverts the declaration does not mention are not
          listed. Turn it on from the mode control in the header.
        </Text>
      ) : null}
      <Text size="xs" c="dimmed">
        <Anchor component={Link} to={`/clusters/${declaration.clusterId}/config-diff`} size="xs">
          Config diff
        </Anchor>{' '}
        compares two nodes with each other; this screen compares every node with the declaration.
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

function NodeCard({
  node,
  clusterId,
  catalogue,
  onRows,
}: {
  node: ConfigNodeStateView;
  clusterId: string;
  catalogue?: ConfigCatalogueView;
  onRows: Set<string>;
}) {
  const state = nodeStateWords(node.state);
  const loose = node.findings.filter((f) => !f.key || !onRows.has(`${f.section}:${f.key}`));
  const groups = new Map<string, typeof node.findings>();
  for (const f of loose) {
    const list = groups.get(f.kind) ?? [];
    list.push(f);
    groups.set(f.kind, list);
  }
  const kinds = [...groups.keys()].sort((a, b) => KIND_ORDER.indexOf(a) - KIND_ORDER.indexOf(b));

  return (
    <Stack gap="xs">
      <div className={classes.nodeLine}>
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
      </div>
      {kinds.map((kind) => (
        <Table key={kind} fz="xs" verticalSpacing={4} withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th style={{ width: 160 }}>{findingKindWords(kind)}</Table.Th>
              <Table.Th>Item</Table.Th>
              <Table.Th>On {node.nodeName}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {groups.get(kind)!.map((f, i) => {
              const rows = findingRows(f, catalogue);
              const ordered = [...rows.filter((r) => r.differs), ...rows.filter((r) => !r.differs)];
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
                      {ordered.length === 0
                        ? '—'
                        : ordered.map((r) => (
                            <div key={r.key} className={classes.kvRow} data-differs={r.differs || undefined}>
                              <span className={classes.kvKey}>{r.key}</span>
                              <span className={classes.kvValue}>{r.observed === '—' ? r.declared : r.observed}</span>
                            </div>
                          ))}
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

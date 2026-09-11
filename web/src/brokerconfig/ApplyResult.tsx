import { Anchor, Stack, Table, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import type { ConfigApplyOutcomeView, ConfigNodeApplyView } from '../api/client.ts';
import { OutcomeSummary, type OutcomeRow } from '../shared/NodeOutcomeSummary.tsx';
import classes from './Configuration.module.css';
import { stepStatusWords, wireSectionLabel } from './words.ts';

/** The one line stating the shape of the result, before any row (frontend rule: four outcomes). */
function verdictFor(o: ConfigApplyOutcomeView): { text: string; tone?: 'warning' | 'danger' } {
  const targets = o.nodes.filter((n) => n.live);
  const skipped = o.nodes.length - targets.length;
  const suffix = skipped > 0 ? ` · ${skipped} not live, will inherit through replication` : '';
  if (o.dryRun) {
    return {
      text:
        o.plan.stepCount === 0
          ? `Nothing to do: every targeted node already matches revision ${o.revision}${suffix}`
          : `Would apply ${o.plan.stepCount} step${o.plan.stepCount === 1 ? '' : 's'} to ${targets.length} live node${targets.length === 1 ? '' : 's'}, canary first${suffix}`,
    };
  }
  switch (o.outcome) {
    case 'APPLIED':
      return { text: `Applied to all ${targets.length} live node${targets.length === 1 ? '' : 's'}${suffix}` };
    case 'HALTED':
      return { text: 'Halted — applied to some nodes and not others', tone: 'warning' };
    case 'FAILED':
      return { text: 'Failed — nothing was applied', tone: 'danger' };
    default:
      return { text: o.outcome };
  }
}

function nodeRow(node: ConfigNodeApplyView): OutcomeRow {
  const applied = node.steps.filter((s) => s.status === 'APPLIED').length;
  const already = node.steps.filter((s) => s.status === 'ALREADY').length;
  const failed = node.steps.filter((s) => s.status === 'FAILED').length;
  const notAttempted = node.steps.filter((s) => s.status === 'NOT_ATTEMPTED').length;
  const would = node.steps.filter((s) => s.status === 'WOULD_APPLY').length;
  const mismatch = node.steps.filter((s) => s.verified === 'MISMATCH').length;

  if (!node.live) {
    return { key: node.nodeId, name: node.nodeName, status: 'skipped — not live', tone: 'warning', detail: node.unavailableReason };
  }
  const name = node.canary ? `${node.nodeName} (canary)` : node.nodeName;
  if (would > 0) return { key: node.nodeId, name, status: `${would} step${would === 1 ? '' : 's'} would apply`, count: String(node.steps.length), detail: node.note };
  if (failed > 0) {
    return {
      key: node.nodeId,
      name,
      status: `failed at step ${node.steps.findIndex((s) => s.status === 'FAILED') + 1} of ${node.steps.length}`,
      tone: 'danger',
      detail: node.steps.find((s) => s.status === 'FAILED')?.error ?? node.note,
    };
  }
  if (mismatch > 0) return { key: node.nodeId, name, status: 'applied — read back differs', tone: 'danger', detail: node.note };
  if (notAttempted > 0 && applied === 0) return { key: node.nodeId, name, status: 'not attempted', tone: 'warning', detail: node.note };
  if (notAttempted > 0) return { key: node.nodeId, name, status: `${applied} applied, ${notAttempted} not attempted`, tone: 'warning', detail: node.note };
  if (applied === 0 && already === node.steps.length) return { key: node.nodeId, name, status: 'already as declared', detail: node.note };
  return { key: node.nodeId, name, status: `${applied} applied and verified${already ? `, ${already} already` : ''}`, detail: node.note };
}

/**
 * An apply's outcome — preview or result — as the same object, so what was
 * confirmed and what happened line up. The per-node summary is the house
 * shape; the per-step table underneath carries before → after and each step's
 * status in words.
 */
export function ApplyResult({
  outcome,
  clusterId,
}: {
  outcome: ConfigApplyOutcomeView;
  clusterId?: string;
}) {
  const verdict = verdictFor(outcome);
  return (
    <Stack gap="md">
      <OutcomeSummary verdict={verdict.text} verdictTone={verdict.tone} rows={outcome.nodes.map(nodeRow)} />
      {!outcome.dryRun && outcome.summary ? (
        <Text size="sm" className={classes.state} data-tone={verdict.tone}>
          {outcome.summary}
        </Text>
      ) : null}
      {outcome.nodes
        .filter((n) => n.live && n.steps.length > 0)
        .map((node) => {
          const planned = outcome.plan.nodes.find((p) => p.nodeId === node.nodeId);
          return (
            <Stack gap={4} key={node.nodeId}>
              <Text size="xs" fw={600}>
                {node.nodeName}
                {node.canary ? ' — canary' : ''}
              </Text>
              <Table fz="xs" verticalSpacing={4} withTableBorder>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th style={{ width: 32 }}>#</Table.Th>
                    <Table.Th>Step</Table.Th>
                    <Table.Th>Before</Table.Th>
                    <Table.Th>After</Table.Th>
                    <Table.Th style={{ width: 200 }}>Status</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {node.steps.map((step, i) => {
                    const plan = planned?.steps.find((s) => s.id === step.stepId);
                    const words = stepStatusWords(step);
                    return (
                      <Table.Tr key={step.stepId}>
                        <Table.Td className={classes.compare}>{i + 1}</Table.Td>
                        <Table.Td>
                          <Text size="xs">{step.description}</Text>
                          <Text size="xs" c="dimmed">
                            {step.op.toLowerCase()} {wireSectionLabel(step.section)} {step.key}
                          </Text>
                        </Table.Td>
                        <Table.Td className={classes.before}>{plan ? kv(plan.before) : '—'}</Table.Td>
                        <Table.Td className={classes.after}>{plan ? kv(plan.after) : '—'}</Table.Td>
                        <Table.Td>
                          <Text size="xs" className={classes.state} data-tone={words.tone}>
                            {words.text}
                          </Text>
                          {step.error ? (
                            <Text size="xs" c="var(--as-danger)">
                              {step.error}
                            </Text>
                          ) : null}
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
            </Stack>
          );
        })}
      {clusterId && outcome.auditEventId != null && !outcome.dryRun ? (
        <Text size="xs" c="dimmed">
          Recorded as audit event {outcome.auditEventId} —{' '}
          <Anchor component={Link} to={`/clusters/${clusterId}/audit?action=APPLY_BROKER_CONFIG`} size="xs">
            open the audit log
          </Anchor>
          .
        </Text>
      ) : null}
    </Stack>
  );
}

function kv(values: Record<string, unknown>): string {
  const entries = Object.entries(values);
  if (entries.length === 0) return '—';
  return entries
    .map(([k, v]) => `${k}=${Array.isArray(v) ? v.join(',') : typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)}`)
    .join(' · ');
}

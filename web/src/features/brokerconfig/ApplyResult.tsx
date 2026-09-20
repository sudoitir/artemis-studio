import { useState } from 'react';
import { Accordion, Anchor, Chip, Group, Stack, Table, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import type { ConfigApplyOutcomeView, ConfigNodeApplyView } from './api.ts';
import { OutcomeSummary, type OutcomeRow } from '../../ui/NodeOutcomeSummary.tsx';
import classes from './Configuration.module.css';
import { stepStatusWords, wireSectionLabel } from './words.ts';

/** A step the broker already agrees with: no write, in a preview or in a result. */
function isAlready(step: { status: string }): boolean {
  return step.status === 'ALREADY';
}

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
  // No count column: the status already carries the number that matters, and a
  // second one beside it — the plan's whole step count, most of which writes
  // nothing — read as a contradiction of it.
  if (would > 0)
    return {
      key: node.nodeId,
      name,
      status: `${would} step${would === 1 ? '' : 's'} would apply${already ? `, ${already} already as declared` : ''}`,
      detail: node.note,
    };
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
  focus,
}: {
  outcome: ConfigApplyOutcomeView;
  clusterId?: string;
  /** Narrow the step tables to one key on arrival — what drift's "Plan a fix" hands over. */
  focus?: string;
}) {
  const verdict = verdictFor(outcome);

  // A plan over a whole cluster is a long list, and the operator is usually
  // looking for one match. Filtering is a view of the plan, never of what will
  // run: the summary above the chips always counts every step.
  const [sections, setSections] = useState<string[]>([]);
  const [keys, setKeys] = useState<string[]>(focus ? [focus] : []);
  // A step whose observed state already matches writes nothing (ADR-0067 D3).
  // Most of a plan is usually these, and reading past them to find the writes is
  // the work this screen exists to save — so they fold away, counted, behind a
  // control that says how many they are.
  const [showAlready, setShowAlready] = useState(false);
  const alreadyCount = outcome.nodes.reduce((n, node) => n + node.steps.filter(isAlready).length, 0);
  const allSections = [...new Set(outcome.nodes.flatMap((n) => n.steps.map((s) => s.section)))];
  const allKeys = [...new Set(outcome.nodes.flatMap((n) => n.steps.map((s) => s.key)))].sort();
  const filtering = sections.length > 0 || keys.length > 0;
  const withSteps = outcome.nodes.filter((n) => n.live && n.steps.length > 0);
  // The canary is always open: it is the node that decides whether the rest run
  // at all. So is any node that failed or read back wrong — a collapsed section
  // is a fine way to shorten a long plan and a terrible way to report a halt. A
  // one- or two-node cluster opens whole; there is nothing to shorten.
  const openByDefault = withSteps
    .filter(
      (n) =>
        withSteps.length <= 2 ||
        n.canary ||
        n.steps.some((s) => s.status === 'FAILED' || s.verified === 'MISMATCH'),
    )
    .map((n) => n.nodeId);
  const shows = (step: { section: string; key: string; status: string }) =>
    (showAlready || !isAlready(step)) &&
    (sections.length === 0 || sections.includes(step.section)) &&
    (keys.length === 0 || keys.includes(step.key));

  return (
    <Stack gap="md">
      <OutcomeSummary verdict={verdict.text} verdictTone={verdict.tone} rows={outcome.nodes.map(nodeRow)} />
      {allKeys.length > 1 || alreadyCount > 0 ? (
        <Group gap="xs" align="center" wrap="wrap">
          <Text size="xs" c="dimmed">
            Show
          </Text>
          <Chip.Group multiple value={sections} onChange={setSections}>
            {allSections.map((section) => (
              <Chip key={section} value={section} size="xs">
                {wireSectionLabel(section)}
              </Chip>
            ))}
          </Chip.Group>
          {alreadyCount > 0 ? (
            <Anchor component="button" type="button" size="xs" onClick={() => setShowAlready((s) => !s)}>
              {showAlready
                ? `Hide the ${alreadyCount} already as declared`
                : `Show the ${alreadyCount} already as declared`}
            </Anchor>
          ) : null}
          {filtering ? (
            <Anchor
              component="button"
              type="button"
              size="xs"
              onClick={() => {
                setSections([]);
                setKeys([]);
              }}
            >
              Clear the filter
            </Anchor>
          ) : null}
        </Group>
      ) : null}
      {!outcome.dryRun && outcome.summary ? (
        <Text size="sm" className={classes.state} data-tone={verdict.tone}>
          {outcome.summary}
        </Text>
      ) : null}
      {/* Filtered-empty is not empty (frontend rule): a node whose steps all fall
          outside the filter says so instead of vanishing from the page. */}
      {withSteps
        .filter((node) => node.steps.filter(shows).length === 0)
        .map((node) => (
          <Text key={node.nodeId} size="xs" c="dimmed">
            {node.nodeName}:{' '}
            {node.steps.every(isAlready)
              ? `all ${node.steps.length} step${node.steps.length === 1 ? ' is' : 's are'} already as declared.`
              : `none of its ${node.steps.length} step${node.steps.length === 1 ? '' : 's'} match the filter.`}
          </Text>
        ))}
      <Accordion multiple defaultValue={openByDefault} variant="contained" chevronPosition="left">
        {withSteps
          .filter((node) => node.steps.filter(shows).length > 0)
          .map((node) => {
          const planned = outcome.plan.nodes.find((p) => p.nodeId === node.nodeId);
          const shown = node.steps.filter(shows);
          return (
            <Accordion.Item value={node.nodeId} key={node.nodeId}>
              <Accordion.Control>
                <Text size="xs" fw={600} component="span">
                  {node.nodeName}
                  {node.canary ? ' — canary' : ''}
                </Text>{' '}
                <Text size="xs" c="dimmed" component="span">
                  {shown.length === node.steps.length
                    ? `${node.steps.length} step${node.steps.length === 1 ? '' : 's'}`
                    : `${shown.length} of ${node.steps.length} steps shown`}
                </Text>
              </Accordion.Control>
              <Accordion.Panel>
              <Table fz="xs" verticalSpacing={4} withTableBorder layout="fixed">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th w={32}>#</Table.Th>
                    <Table.Th w="34%">Step</Table.Th>
                    <Table.Th w="40%">Change</Table.Th>
                    <Table.Th w="20%">Status</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {shown.map((step) => {
                    // The number is the step's place in the plan, not in the
                    // filtered view: it is what the halt message refers to.
                    const i = node.steps.indexOf(step);
                    const plan = planned?.steps.find((s) => s.id === step.stepId);
                    const words = stepStatusWords(step);
                    return (
                      <Table.Tr key={step.stepId}>
                        <Table.Td className={classes.stepNumber}>{i + 1}</Table.Td>
                        <Table.Td>
                          <Text size="xs">{step.description}</Text>
                          <Text size="xs" c="dimmed">
                            {step.op.toLowerCase()} {wireSectionLabel(step.section)} {step.key}
                          </Text>
                        </Table.Td>
                        <Table.Td className={classes.compare}>
                          {plan ? <Diff before={plan.before} after={plan.after} /> : '—'}
                        </Table.Td>
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
              </Accordion.Panel>
            </Accordion.Item>
          );
        })}
      </Accordion>
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

function one(value: unknown): string {
  if (value === undefined) return '—';
  if (Array.isArray(value)) return value.length === 0 ? '—' : value.join(',');
  if (typeof value === 'object' && value !== null) return JSON.stringify(value);
  return String(value);
}

/**
 * One step as a diff: a row per key, `before → after`, with the keys that do not
 * change dimmed underneath rather than hidden.
 *
 * Two columns of `k=v · k=v` made the reader do the comparison — on an address
 * setting carrying eighteen keys of which one moves, the one that moves is not
 * findable. The unchanged keys stay because a management write **replaces** the
 * whole entry (notes §15 M2): they are not context, they are part of what is
 * being written.
 */
function Diff({ before, after }: { before: Record<string, unknown>; after: Record<string, unknown> }) {
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort();
  if (keys.length === 0) return <>—</>;
  // Nothing is there yet, so every key would read `— → value`. The step's own
  // description already says it creates the thing; what is worth reading is what
  // it will be created as.
  if (Object.keys(before).length === 0) {
    return (
      <div className={classes.kv}>
        {keys.map((key) => (
          <div key={key} className={classes.kvRow}>
            <span className={classes.kvKey}>{key}</span>
            <span className={classes.kvValue}>{one(after[key])}</span>
          </div>
        ))}
      </div>
    );
  }
  const rows = keys.map((key) => ({
    key,
    before: one(before[key]),
    after: one(after[key]),
    differs: one(before[key]) !== one(after[key]),
  }));
  const ordered = [...rows.filter((r) => r.differs), ...rows.filter((r) => !r.differs)];
  return (
    <div className={classes.kv} data-diff>
      {ordered.map((r) => (
        <div key={r.key} className={classes.kvRow} data-differs={r.differs || undefined}>
          <span className={classes.kvKey}>{r.key}</span>
          <span className={classes.kvValue}>
            {r.differs ? (
              <>
                <span className={classes.before}>{r.before}</span>
                {' → '}
                {r.after}
              </>
            ) : (
              r.after
            )}
          </span>
        </div>
      ))}
    </div>
  );
}

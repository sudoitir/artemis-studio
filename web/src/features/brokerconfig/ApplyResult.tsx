import { useMemo, useState } from 'react';
import { Accordion, Button, Chip, Group, Stack, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import type { ConfigApplyOutcomeView, ConfigNodeApplyView } from './api.ts';
import linkClasses from '../../ui/InlineLink.module.css';
import { OutcomeSummary, type OutcomeRow } from '../../ui/NodeOutcomeSummary.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { stepColumns, type StepRow } from './stepColumns.tsx';
import { wireSectionLabel } from './words.ts';

const plural = (n: number) => (n === 1 ? '' : 's');

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
    if (o.plan.stepCount === 0) {
      return { text: `Nothing to do: every targeted node already matches revision ${o.revision}${suffix}` };
    }
    return {
      text: `Would apply ${o.plan.stepCount} step${plural(o.plan.stepCount)} to ${targets.length} live node${plural(targets.length)}, canary first${suffix}`,
    };
  }
  switch (o.outcome) {
    case 'APPLIED':
      return { text: `Applied to all ${targets.length} live node${plural(targets.length)}${suffix}` };
    case 'HALTED':
      return { text: 'Halted — applied to some nodes and not others', tone: 'warning' };
    case 'FAILED':
      return { text: 'Failed — nothing was applied', tone: 'danger' };
    default:
      return { text: o.outcome };
  }
}

/** Why a node shows no step: every one is already as declared, or none matches the filter. */
function hiddenStepsNote(steps: { status: string }[]): string {
  const n = steps.length;
  if (steps.every(isAlready)) return `all ${n} step${n === 1 ? ' is' : 's are'} already as declared.`;
  return `none of its ${n} step${plural(n)} match the filter.`;
}

/** ", 3 already as declared" — the steps a broker already agrees with, when there are any. */
function alreadyNote(already: number, tail: string): string {
  return already ? `, ${already} already${tail}` : '';
}

function nodeRow(node: ConfigNodeApplyView): OutcomeRow {
  const applied = node.steps.filter((s) => s.status === 'APPLIED').length;
  const already = node.steps.filter((s) => s.status === 'ALREADY').length;
  const failed = node.steps.filter((s) => s.status === 'FAILED').length;
  const notAttempted = node.steps.filter((s) => s.status === 'NOT_ATTEMPTED').length;
  const would = node.steps.filter((s) => s.status === 'WOULD_APPLY').length;
  const mismatch = node.steps.filter((s) => s.verified === 'MISMATCH').length;

  if (!node.live) {
    return {
      key: node.nodeId,
      name: node.nodeName,
      status: 'skipped — not live',
      tone: 'warning',
      detail: node.unavailableReason,
    };
  }
  const name = node.canary ? `${node.nodeName} (canary)` : node.nodeName;
  // No count column: the status already carries the number that matters, and a
  // second one beside it — the plan's whole step count, most of which writes
  // nothing — read as a contradiction of it.
  if (would > 0)
    return {
      key: node.nodeId,
      name,
      status: `${would} step${plural(would)} would apply${alreadyNote(already, ' as declared')}`,
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
  if (mismatch > 0)
    return { key: node.nodeId, name, status: 'applied — read back differs', tone: 'danger', detail: node.note };
  if (notAttempted > 0 && applied === 0)
    return { key: node.nodeId, name, status: 'not attempted', tone: 'warning', detail: node.note };
  if (notAttempted > 0)
    return {
      key: node.nodeId,
      name,
      status: `${applied} applied, ${notAttempted} not attempted`,
      tone: 'warning',
      detail: node.note,
    };
  if (applied === 0 && already === node.steps.length)
    return { key: node.nodeId, name, status: 'already as declared', detail: node.note };
  return {
    key: node.nodeId,
    name,
    status: `${applied} applied and verified${alreadyNote(already, '')}`,
    detail: node.note,
  };
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
}: Readonly<{
  outcome: ConfigApplyOutcomeView;
  clusterId?: string;
  /** Narrow the step tables to one key on arrival — what drift's "Plan a fix" hands over. */
  focus?: string;
}>) {
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
  const allKeys = [...new Set(outcome.nodes.flatMap((n) => n.steps.map((s) => s.key)))].sort((a, b) =>
    a.localeCompare(b),
  );
  const filtering = sections.length > 0 || keys.length > 0;
  const withSteps = outcome.nodes.filter((n) => n.live && n.steps.length > 0);
  // The canary is always open: it is the node that decides whether the rest run
  // at all. So is any node that failed or read back wrong — a collapsed section
  // is a fine way to shorten a long plan and a terrible way to report a halt. A
  // one- or two-node cluster opens whole; there is nothing to shorten.
  const openByDefault = withSteps
    .filter(
      (n) =>
        withSteps.length <= 2 || n.canary || n.steps.some((s) => s.status === 'FAILED' || s.verified === 'MISMATCH'),
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
          <Text size="sm" c="dimmed">
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
            <Button variant="subtle" size="compact-sm" onClick={() => setShowAlready((s) => !s)}>
              {showAlready
                ? `Hide the ${alreadyCount} already as declared`
                : `Show the ${alreadyCount} already as declared`}
            </Button>
          ) : null}
          {filtering ? (
            <Button
              variant="subtle"
              size="compact-sm"
              onClick={() => {
                setSections([]);
                setKeys([]);
              }}
            >
              Clear the filter
            </Button>
          ) : null}
        </Group>
      ) : null}
      {!outcome.dryRun && outcome.summary ? <Text size="sm">{outcome.summary}</Text> : null}
      {/* Filtered-empty is not empty (frontend rule): a node whose steps all fall
          outside the filter says so instead of vanishing from the page. */}
      {withSteps
        .filter((node) => !node.steps.some(shows))
        .map((node) => (
          <Text key={node.nodeId} size="sm" c="dimmed">
            {node.nodeName}: {hiddenStepsNote(node.steps)}
          </Text>
        ))}
      <Accordion multiple defaultValue={openByDefault} variant="contained" chevronPosition="left" order={4}>
        {withSteps
          .filter((node) => node.steps.some(shows))
          .map((node) => {
            const planned = outcome.plan.nodes.find((p) => p.nodeId === node.nodeId);
            const shown = node.steps.filter(shows);
            return (
              <Accordion.Item value={node.nodeId} key={node.nodeId}>
                <Accordion.Control>
                  <Text size="sm" fw={600} component="span">
                    {node.nodeName}
                    {node.canary ? ' — canary' : ''}
                  </Text>{' '}
                  <Text size="sm" c="dimmed" component="span">
                    {shown.length === node.steps.length
                      ? `${node.steps.length} step${plural(node.steps.length)}`
                      : `${shown.length} of ${node.steps.length} steps shown`}
                  </Text>
                </Accordion.Control>
                <Accordion.Panel>
                  <NodeSteps
                    nodeName={node.nodeName}
                    // The number is the step's place in the plan, not in the filtered view: it is
                    // what the halt message refers to.
                    rows={shown.map((step) => ({
                      step,
                      number: node.steps.indexOf(step) + 1,
                      plan: planned?.steps.find((s) => s.id === step.stepId),
                    }))}
                  />
                </Accordion.Panel>
              </Accordion.Item>
            );
          })}
      </Accordion>
      {clusterId && outcome.auditEventId != null && !outcome.dryRun ? (
        <Text size="sm" c="dimmed">
          Recorded as audit event {outcome.auditEventId} —{' '}
          <Link to={`/clusters/${clusterId}/audit?action=APPLY_BROKER_CONFIG`} className={linkClasses.link}>
            open the audit log
          </Link>
          .
        </Text>
      ) : null}
    </Stack>
  );
}

/** One node's steps as a table: the step, what it changes, and how it ended. */
function NodeSteps({ nodeName, rows }: Readonly<{ nodeName: string; rows: StepRow[] }>) {
  const columns = useMemo(stepColumns, []);
  return (
    <DataTable
      variant="static"
      label={`Steps on ${nodeName}`}
      columns={columns}
      data={rows}
      rowKey={(r) => r.step.stepId}
      storageKey="brokerconfig.apply.steps"
      height={{ maxRows: rows.length }}
      empty={null}
    />
  );
}

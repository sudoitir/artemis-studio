import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Checkbox, Drawer, Group, Select, Stack, Switch, Text, VisuallyHidden } from '@mantine/core';

import {
  useApplyBrokerConfig,
  type ConfigApplyOutcomeView,
  type ConfigApplyRequest,
  type ConfigDeclarationView,
} from './api.ts';
import type { ApiError } from '../../kernel/api/request.ts';
import { useCluster } from '../clusters/index.ts';
import { clearApplyProgress, useApplyProgress } from './applyProgress.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { ApplyResult } from './ApplyResult.tsx';
import { ApplyTimeline } from './ApplyTimeline.tsx';
import { Notice } from './Notice.tsx';
import classes from './Configuration.module.css';
import { CONFIG_MANAGED_REASON, hazardClassWords, wireSectionLabel } from './words.ts';

export const APPLY_PERMISSION_LABEL = 'Apply declared configuration';

/** What an apply covers: the whole declaration, or one row's item (ADR-0087 D2). */
export interface ApplyScope {
  /** "address setting orders.#", or absent for the whole declaration. */
  label?: string;
  /** Plan step identifiers; empty runs the whole plan. */
  stepIds?: string[];
}

type Stage = 'plan' | 'confirm' | 'result';

/**
 * Plan → Confirm → Result (ADR-0067 D3, D4, D7), as a drawer over the rows it
 * changes (ADR-0087 D2).
 *
 * <p>The plan is a dry run, made when the drawer opens and again whenever the
 * target set changes. The real run names the plan hash it was shown, so a cluster
 * that moved between preview and apply is refused rather than acted on — and when
 * the apply is scoped to one item, the hash is the server's hash of that narrowed
 * plan, so the confirmation still covers exactly the run. High hazards are
 * acknowledged one by one; that is acknowledgement, not confirmation. The
 * confirmation is the cluster's name, typed.
 *
 * <p>Four outcomes are rendered: pending (the control is busy), applied, halted
 * (partial — the one that matters most), failed. The result uses the same
 * component as the plan, so the two compare row by row.
 */
export function ReviewApplyDrawer({
  declaration,
  scope,
  opened,
  onClose,
}: Readonly<{
  declaration: ConfigDeclarationView;
  scope: ApplyScope | null;
  opened: boolean;
  onClose: () => void;
}>) {
  const flow = useApplyFlow(declaration, scope, opened);
  const { stage, previewed, result } = flow;
  const title = scope?.label ? `Review & apply ${scope.label}` : `Review & apply revision ${flow.revision}`;

  return (
    <Drawer opened={opened} onClose={onClose} title={title} position="right" size="xl" padding="md">
      {/* Only the stage sentence is announced: a live region around the whole
          form re-reads the plan every time a checkbox moves, which buries the
          outcome it exists to carry. */}
      <VisuallyHidden aria-live="polite">{stageAnnouncement(flow)}</VisuallyHidden>
      <div>
        <Stack gap="md">
          {scope?.label ? (
            <Text size="sm" c="dimmed">
              Scoped to {scope.label} on every targeted node. Everything else this cluster has pending stays pending.
            </Text>
          ) : null}

          {stage === 'result' ? null : <PlanStage flow={flow} declaration={declaration} scope={scope} />}
          {stage === 'confirm' && previewed ? (
            <ConfirmStage flow={flow} previewed={previewed} declaration={declaration} />
          ) : null}
          {stage === 'result' && result ? (
            <ResultStage flow={flow} result={result} clusterId={declaration.clusterId} onClose={onClose} />
          ) : null}
        </Stack>
      </div>
    </Drawer>
  );
}

const plural = (n: number) => (n === 1 ? '' : 's');

type Hazard = ConfigApplyOutcomeView['plan']['hazards'][number];

/** Whether the apply is planning (a dry run) or running for real; the two are told apart by `dryRun`. */
function applyActivity(apply: ReturnType<typeof useApplyBrokerConfig>) {
  return {
    planning: apply.isPending && apply.variables?.dryRun,
    running: apply.isPending && apply.variables?.dryRun === false,
  };
}

/** The request the current choices make: the nodes, the canary, the hazards acknowledged so far. */
function applyBody(
  revision: number,
  choices: {
    nodeIds: string[] | null;
    canary: string | null;
    removeUndeclared: boolean;
    acknowledged: Set<string>;
    previewed: ConfigApplyOutcomeView | null;
    stepIds?: string[];
  },
): ConfigApplyRequest {
  return {
    revision,
    nodeIds: choices.nodeIds?.length ? choices.nodeIds : undefined,
    canaryNodeId: choices.canary ?? undefined,
    removeUndeclared: choices.removeUndeclared,
    acknowledgedHazards: [...choices.acknowledged],
    expectedPlanHash: choices.previewed?.plan.planHash ?? undefined,
    stepIds: choices.stepIds,
  };
}

/** Changes whenever what would be planned changes, so the drawer re-plans exactly then. */
function planKey(
  revision: number,
  targets: string[],
  canary: string | null,
  removeUndeclared: boolean,
  stepIds?: string[],
) {
  return `${revision}:${targets.join(',')}:${canary ?? ''}:${removeUndeclared}:${(stepIds ?? []).join(',')}`;
}

/** The permission gate, closed outright while the file owns the configuration. */
function useApplyGate(declaration: ConfigDeclarationView): GateVerdict {
  const clusterId = declaration.clusterId;
  const cluster = useCluster(clusterId);
  const { can, loading } = useCan();
  const permissionGate = gateFor(
    can('config:apply', clusterId),
    APPLY_PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  if (declaration.applyMode === 'CONFIG_MANAGED') return { kind: 'blocked', reason: CONFIG_MANAGED_REASON };
  return permissionGate;
}

/** The plan → confirm → result state machine, and every choice the operator makes along the way. */
function useApplyFlow(declaration: ConfigDeclarationView, scope: ApplyScope | null, opened: boolean) {
  const apply = useApplyBrokerConfig(declaration.clusterId);
  const gate = useApplyGate(declaration);

  const [stage, setStage] = useState<Stage>('plan');
  const [previewed, setPreviewed] = useState<ConfigApplyOutcomeView | null>(null);
  const [result, setResult] = useState<ConfigApplyOutcomeView | null>(null);
  const [nodeIds, setNodeIds] = useState<string[] | null>(null);
  const [canary, setCanary] = useState<string | null>(null);
  const [removeUndeclared, setRemoveUndeclared] = useState(false);
  const [acknowledged, setAcknowledged] = useState<Set<string>>(new Set());
  const [override, setOverride] = useState(false);
  const [planError, setPlanError] = useState<ApiError | null>(null);
  const [moved, setMoved] = useState(false);

  const liveNodes = useMemo(() => declaration.nodes.filter((n) => n.live), [declaration.nodes]);
  const targets = nodeIds ?? liveNodes.map((n) => n.nodeId);
  // An empty node set means "every node" on the wire, so an empty selection is
  // never sent: it would plan and apply the whole cluster under a confirmation
  // that says nought nodes.
  const noNodes = targets.length === 0;
  const revision = declaration.revision;
  const stepIds = scope?.stepIds;

  const body = () => applyBody(revision, { nodeIds, canary, removeUndeclared, acknowledged, previewed, stepIds });

  const preview = (then: (outcome: ConfigApplyOutcomeView, previousHash?: string) => void) => {
    const previousHash = previewed?.plan.planHash;
    setPlanError(null);
    apply.mutate(
      { body: { ...body(), acknowledgedHazards: [], expectedPlanHash: undefined }, dryRun: true, override: true },
      { onSuccess: (o) => then(o, previousHash), onError: (e) => setPlanError(e) },
    );
  };

  const runPlan = () =>
    preview((o) => {
      setPreviewed(o);
      setAcknowledged(new Set());
      setResult(null);
      setStage('plan');
    });

  // Plan when the drawer opens, and again when the targets change. The
  // declaration's own refetches do not re-plan: the hash guards the real run.
  const targetKey = planKey(revision, targets, canary, removeUndeclared, stepIds);
  const planNow = useRef(runPlan);
  planNow.current = runPlan;
  useEffect(() => {
    if (!opened || !declaration.declared) return;
    if (noNodes) {
      setPreviewed(null);
      setStage('plan');
      return;
    }
    planNow.current();
  }, [opened, declaration.declared, noNodes, targetKey]);

  // A closed drawer holds nothing: the next apply is a new question, and a stale
  // plan behind a closed drawer is the one an operator would confirm by habit.
  useEffect(() => {
    if (opened) return;
    setStage('plan');
    setPreviewed(null);
    setResult(null);
    setAcknowledged(new Set());
    setMoved(false);
    setPlanError(null);
  }, [opened]);

  /**
   * Re-plan on the way into the confirmation, and compare the hash.
   *
   * The real run is refused server-side when the cluster moved (ADR-0067 D12), but
   * by then the operator has typed the cluster's name to confirm a plan that no
   * longer exists, and the refusal arrives as a 409 they did not cause. Checking
   * here turns that into a sentence before the confirmation, with the new plan
   * already on the screen. Acknowledgements are dropped with the plan they
   * belonged to: they were made about hazards that may no longer be the hazards.
   */
  const continueToConfirm = () =>
    preview((o, previousHash) => {
      setPreviewed(o);
      if (o.plan.planHash === previousHash) {
        setMoved(false);
        setStage('confirm');
        return;
      }
      setMoved(true);
      setAcknowledged(new Set());
      setStage('plan');
    });

  const run = () => {
    // A previous run's tail must not be read as this run's progress.
    clearApplyProgress();
    apply.mutate(
      { body: body(), dryRun: false, override },
      {
        onSuccess: (o) => {
          setResult(o);
          setStage('result');
        },
      },
    );
  };

  return {
    apply,
    gate,
    stage,
    setStage,
    previewed,
    result,
    nodeIds,
    setNodeIds,
    canary,
    setCanary,
    removeUndeclared,
    setRemoveUndeclared,
    acknowledged,
    setAcknowledged,
    override,
    setOverride,
    planError,
    moved,
    liveNodes,
    targets,
    noNodes,
    revision,
    runPlan,
    continueToConfirm,
    run,
  };
}

type Flow = ReturnType<typeof useApplyFlow>;

const RESULT_ANNOUNCEMENT: Record<string, string> = {
  APPLIED: 'Applied to every targeted node.',
  HALTED: 'Halted partway: some nodes were written and others were not.',
};

/** The one sentence a screen reader hears for the stage the drawer is in. */
function stageAnnouncement(flow: Flow): string {
  const { planning, running } = applyActivity(flow.apply);
  if (flow.stage === 'result' && flow.result) {
    return RESULT_ANNOUNCEMENT[flow.result.outcome] ?? 'The apply failed; nothing was written.';
  }
  if (running) return 'Applying, canary first.';
  if (planning) return 'Planning the apply.';
  if (flow.noNodes) return 'No node is selected, so nothing is planned.';
  if (!flow.previewed) return '';
  const steps = flow.previewed.plan.stepCount;
  const nodes = flow.targets.length;
  return `Plan ready: ${steps} step${plural(steps)} on ${nodes} node${plural(nodes)}. Nothing has been written.`;
}

/** What still stands between the operator and the confirmation, each stated in words. */
function planBlockers(flow: Flow, unacknowledged: Hazard[]): string[] {
  const { gate, previewed, override } = flow;
  const blockers: string[] = [];
  if (gate.kind === 'blocked') blockers.push(gate.reason);
  if (unacknowledged.length > 0) {
    blockers.push(`${unacknowledged.length} High hazard${plural(unacknowledged.length)} not yet acknowledged above.`);
  }
  if (previewed?.overCap && !override) {
    blockers.push(
      `The plan has ${previewed.plan.stepCount} steps, over the cap of ${previewed.stepCap}. Override it above to continue.`,
    );
  }
  if (previewed?.plan.stepCount === 0) blockers.push('Nothing to apply: every targeted node already matches.');
  return blockers;
}

/** The plan stage: the nodes, the plan itself, its hazards, and the way on to the confirmation. */
function PlanStage({
  flow,
  declaration,
  scope,
}: Readonly<{ flow: Flow; declaration: ConfigDeclarationView; scope: ApplyScope | null }>) {
  const { apply, previewed, planError, moved, noNodes } = flow;
  const { planning } = applyActivity(apply);
  return (
    <Stack gap="md">
      {planning ? <Text size="sm">Planning — reading every live node…</Text> : null}
      {!previewed && apply.isPending ? <LoadingState label="Planning the apply" blockSize="8rem" /> : null}
      {planError ? (
        <Stack gap="sm">
          <ErrorState error={planError} onRetry={flow.runPlan} />
          <Text size="sm">Fix the declaration and come back; nothing was changed.</Text>
        </Stack>
      ) : null}
      {moved ? (
        <Notice alert title="The cluster moved — this is a new plan">
          Something changed on a node between the plan you were reading and the confirmation. The plan below has been
          made again from what the nodes run now; review it, acknowledge its hazards, and continue. Nothing was written.
        </Notice>
      ) : null}

      <NodePicker flow={flow} />

      {noNodes ? (
        <Notice alert title="Select at least one node">
          No node is selected, so there is nothing to plan. Tick the nodes this apply should write to — an empty
          selection is not a shortcut for all of them.
        </Notice>
      ) : null}
      {previewed ? <PlanBody flow={flow} previewed={previewed} declaration={declaration} scope={scope} /> : null}
    </Stack>
  );
}

/** Which live nodes the apply writes to, and which of them goes first. */
function NodePicker({ flow }: Readonly<{ flow: Flow }>) {
  const { liveNodes, targets, canary, previewed, setNodeIds, setCanary } = flow;
  return (
    <Group align="flex-end" gap="md" wrap="wrap">
      <Checkbox.Group
        label="Nodes"
        description="Live nodes to apply to. Every node that is not live is skipped and said so."
        value={targets}
        onChange={(v) => {
          setNodeIds(v.length === liveNodes.length ? null : v);
          if (canary && !v.includes(canary)) setCanary(null);
        }}
      >
        <Group gap="md" mt="xs">
          {liveNodes.map((n) => (
            <Checkbox key={n.nodeId} value={n.nodeId} label={n.nodeName} size="xs" />
          ))}
        </Group>
      </Checkbox.Group>
      <Select
        label="Canary"
        description="Receives every step first and is read back before any other node is touched."
        size="xs"
        data={liveNodes.filter((n) => targets.includes(n.nodeId)).map((n) => ({ value: n.nodeId, label: n.nodeName }))}
        value={canary ?? previewed?.plan.canaryNodeId ?? null}
        onChange={setCanary}
        allowDeselect={false}
        w="12.5rem"
      />
    </Group>
  );
}

/** A live node's name, by id. */
const nodeNameOf = (flow: Flow, id: string | null | undefined) => flow.liveNodes.find((n) => n.nodeId === id)?.nodeName;

/** The plan as the operator reads it: summary bar, hazards, findings, the result table and the way on. */
function PlanBody({
  flow,
  previewed,
  declaration,
  scope,
}: Readonly<{
  flow: Flow;
  previewed: ConfigApplyOutcomeView;
  declaration: ConfigDeclarationView;
  scope: ApplyScope | null;
}>) {
  const { apply, stage, override, setOverride, removeUndeclared, setRemoveUndeclared } = flow;
  const { planning } = applyActivity(apply);
  const highHazards = previewed.plan.hazards.filter((h) => h.hazardClass === 'HIGH');
  const unacknowledged = highHazards.filter((h) => !flow.acknowledged.has(h.id));
  const backups = declaration.nodes.filter((n) => !n.live);
  return (
    <>
      <PlanSummary flow={flow} previewed={previewed} highCount={highHazards.length} unacknowledged={unacknowledged} />

      {/* The step count, the nodes and the canary are in the summary
          bar above; repeating them here only pushed the plan down. What
          the bar cannot say is what happens to a node that is not live. */}
      {backups.length > 0 ? (
        <Text size="sm">
          {backups.map((b) => b.nodeName).join(', ')} {backups.length === 1 ? 'is a backup and' : 'are backups and'}{' '}
          will inherit through replication.
        </Text>
      ) : null}

      {scope?.label ? null : (
        <Switch
          label="Also remove settings and diverts Studio did not apply"
          description="Off: only what Studio applied and is no longer declared is removed. On: undeclared items are removed too — if broker.xml also has one, the removal reverts on the next restart and Studio cannot tell. A High hazard per item."
          size="xs"
          checked={removeUndeclared}
          onChange={(e) => setRemoveUndeclared(e.currentTarget.checked)}
        />
      )}

      <HazardList
        hazards={previewed.plan.hazards}
        highCount={highHazards.length}
        acknowledged={flow.acknowledged}
        setAcknowledged={flow.setAcknowledged}
      />

      {previewed.plan.findings.length > 0 ? (
        <Section headingLevel={3} title="Noticed, not acted on">
          {previewed.plan.findings.map((f) => (
            <Text key={`${f.nodeName}:${f.section}:${f.key}:${f.detail}`} size="sm" c="dimmed">
              {f.nodeName} · {wireSectionLabel(f.section)} {f.key ?? ''} — {f.detail}
            </Text>
          ))}
        </Section>
      ) : null}

      {previewed.overCap ? (
        <Switch
          label={`Override the step cap (${previewed.plan.stepCount} steps, cap ${previewed.stepCap})`}
          description="The cap exists so one apply cannot rewrite a whole cluster by accident. Overriding is recorded in the audit event."
          size="xs"
          checked={override}
          onChange={(e) => setOverride(e.currentTarget.checked)}
        />
      ) : null}

      <Section headingLevel={3} title="Plan">
        <ApplyResult outcome={previewed} />
      </Section>

      {stage === 'plan' ? (
        <Group align="center">
          <Button
            size="xs"
            onClick={flow.continueToConfirm}
            loading={planning}
            disabled={previewed.plan.stepCount === 0}
          >
            Continue to confirm
          </Button>
          {previewed.plan.stepCount === 0 ? (
            <Text size="sm" c="dimmed">
              Nothing to apply: every targeted node already matches revision {flow.revision}.
            </Text>
          ) : null}
          <Button variant="default" size="xs" onClick={flow.runPlan} loading={planning}>
            Plan again
          </Button>
        </Group>
      ) : null}
    </>
  );
}

/** One line of counts, and the state of the acknowledgements beside it. */
function PlanSummary({
  flow,
  previewed,
  highCount,
  unacknowledged,
}: Readonly<{ flow: Flow; previewed: ConfigApplyOutcomeView; highCount: number; unacknowledged: Hazard[] }>) {
  const { canary, targets } = flow;
  const { stepCount, hazards } = previewed.plan;
  return (
    <div className={classes.summaryBar}>
      <Text size="sm">
        <b>{stepCount}</b> step{plural(stepCount)} · {targets.length} node{plural(targets.length)} · canary{' '}
        {nodeNameOf(flow, canary ?? previewed.plan.canaryNodeId) ?? 'first live node'}
        {hazards.length > 0
          ? ` · ${hazards.length} hazard${plural(hazards.length)}, ${highCount} High`
          : ' · no hazards'}
        {previewed.overCap ? ` · over the step cap of ${previewed.stepCap}` : ''}
      </Text>
      <StatusBadge tone={unacknowledged.length > 0 ? 'warning' : 'neutral'}>
        {unacknowledged.length > 0
          ? `${unacknowledged.length} High hazard${plural(unacknowledged.length)} to acknowledge`
          : 'nothing written yet'}
      </StatusBadge>
    </div>
  );
}

/** Every hazard of the plan; each High one must be acknowledged, one by one, before the run is armed. */
function HazardList({
  hazards,
  highCount,
  acknowledged,
  setAcknowledged,
}: Readonly<{
  hazards: Hazard[];
  highCount: number;
  acknowledged: Set<string>;
  setAcknowledged: (update: (prev: Set<string>) => Set<string>) => void;
}>) {
  if (hazards.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No hazards.
      </Text>
    );
  }
  return (
    <Section headingLevel={3} title={`Hazards (${hazards.length}) — ${highCount} High`}>
      {hazards.map((h) => (
        <div key={h.id} className={classes.hazard} data-class={h.hazardClass}>
          <Text size="sm">
            <Text component="span" size="sm" fw={600}>
              {hazardClassWords(h.hazardClass)}
            </Text>{' '}
            · {h.nodeName} · {wireSectionLabel(h.section)} {h.key} — {h.message}
          </Text>
          {h.hazardClass === 'HIGH' ? (
            <Checkbox
              size="xs"
              mt="xs"
              label={`I understand: ${h.kind.toLowerCase().replaceAll('_', ' ')} on ${h.nodeName}`}
              checked={acknowledged.has(h.id)}
              onChange={(e) => {
                const on = e.currentTarget.checked;
                setAcknowledged((prev) => {
                  const next = new Set(prev);
                  if (on) next.add(h.id);
                  else next.delete(h.id);
                  return next;
                });
              }}
            />
          ) : null}
        </div>
      ))}
    </Section>
  );
}

/** What the server's refusal of the real run means for the operator, when it has a known cause. */
function refusalHint(type: string): string {
  if (type.endsWith('plan-changed')) {
    return 'The cluster moved since this plan was made. Plan again and review it before confirming.';
  }
  return type.endsWith('apply-in-progress') ? 'Wait for it to finish, then plan again.' : '';
}

/** The confirmation: what will be written, what still blocks it, and the typed name that arms it. */
function ConfirmStage({
  flow,
  previewed,
  declaration,
}: Readonly<{ flow: Flow; previewed: ConfigApplyOutcomeView; declaration: ConfigDeclarationView }>) {
  const { apply, gate, targets } = flow;
  const { running } = applyActivity(apply);
  const highHazards = previewed.plan.hazards.filter((h) => h.hazardClass === 'HIGH');
  const blockers = planBlockers(
    flow,
    highHazards.filter((h) => !flow.acknowledged.has(h.id)),
  );
  const progress = useApplyProgress();
  return (
    <Section headingLevel={3} title="Confirm">
      <Text size="sm">
        {previewed.plan.stepCount} management write{plural(previewed.plan.stepCount)} on{' '}
        {targets.map((id) => nodeNameOf(flow, id) ?? id).join(', ')}, canary first, halting at the first failure.
        Nothing is rolled back if it halts; re-running converges. Steps that create queues or addresses destroy nothing;
        settings replace the broker's whole entry for their match.
      </Text>
      {blockers.length > 0 ? (
        <Stack gap="xs">
          {blockers.map((b) => (
            <Text key={b} size="sm" c="dimmed">
              {b}
            </Text>
          ))}
        </Stack>
      ) : null}
      {apply.isError && apply.variables?.dryRun === false ? (
        <Stack gap="sm">
          <ErrorState error={apply.error} />
          {refusalHint(apply.error.type) ? <Text size="sm">{refusalHint(apply.error.type)}</Text> : null}
        </Stack>
      ) : null}
      {running ? <ApplyTimeline progress={progress} /> : null}
      <CapabilityGate verdict={gate}>
        <ConfirmByTyping
          token={declaration.clusterName}
          confirmLabel={`Apply to ${targets.length} node${plural(targets.length)}, canary first`}
          loading={running}
          disabled={blockers.length > 0}
          onConfirm={flow.run}
        />
      </CapabilityGate>
      {gate.kind === 'allowed' && gate.uncertain ? (
        <Text size="sm" c="dimmed">
          Whether this connection may write has not been established yet; the broker will say if it refuses.
        </Text>
      ) : null}
      <div>
        <Button variant="subtle" size="compact-sm" onClick={() => flow.setStage('plan')}>
          Back to the plan
        </Button>
      </div>
    </Section>
  );
}

/** The outcome of the real run, and the ways out of it. */
function ResultStage({
  flow,
  result,
  clusterId,
  onClose,
}: Readonly<{ flow: Flow; result: ConfigApplyOutcomeView; clusterId: string; onClose: () => void }>) {
  return (
    <Stack gap="md">
      <Section headingLevel={3} title="Result">
        <ApplyResult outcome={result} clusterId={clusterId} />
      </Section>
      {result.outcome === 'HALTED' || result.outcome === 'FAILED' ? (
        <Text size="sm" c="dimmed">
          Re-running the same revision converges: matching steps are already as declared, failed and not-attempted ones
          are attempted again.
        </Text>
      ) : null}
      <Group>
        <Button size="xs" variant="default" onClick={flow.runPlan}>
          Plan again
        </Button>
        <Button size="xs" onClick={onClose}>
          Back to the configuration
        </Button>
      </Group>
    </Stack>
  );
}

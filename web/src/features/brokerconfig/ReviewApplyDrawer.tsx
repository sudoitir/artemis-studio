import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Drawer, Group, Select, Skeleton, Stack, Switch, Text, VisuallyHidden } from '@mantine/core';

import {
  useApplyBrokerConfig,
  type ConfigApplyOutcomeView,
  type ConfigApplyRequest,
  type ConfigDeclarationView,
} from './api.ts';
import { useCluster } from '../clusters/index.ts';
import { clearApplyProgress, useApplyProgress } from './applyProgress.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { ApplyResult } from './ApplyResult.tsx';
import { ApplyTimeline } from './ApplyTimeline.tsx';
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
}: {
  declaration: ConfigDeclarationView;
  scope: ApplyScope | null;
  opened: boolean;
  onClose: () => void;
}) {
  const clusterId = declaration.clusterId;
  const cluster = useCluster(clusterId);
  const { can, loading } = useCan();
  const apply = useApplyBrokerConfig(clusterId);

  const [stage, setStage] = useState<Stage>('plan');
  const [previewed, setPreviewed] = useState<ConfigApplyOutcomeView | null>(null);
  const [result, setResult] = useState<ConfigApplyOutcomeView | null>(null);
  const [nodeIds, setNodeIds] = useState<string[] | null>(null);
  const [canary, setCanary] = useState<string | null>(null);
  const [removeUndeclared, setRemoveUndeclared] = useState(false);
  const [acknowledged, setAcknowledged] = useState<Set<string>>(new Set());
  const [override, setOverride] = useState(false);
  const [planError, setPlanError] = useState<string | null>(null);
  const [moved, setMoved] = useState(false);

  const progress = useApplyProgress();
  const liveNodes = useMemo(() => declaration.nodes.filter((n) => n.live), [declaration.nodes]);
  const targets = nodeIds ?? liveNodes.map((n) => n.nodeId);
  // An empty node set means "every node" on the wire, so an empty selection is
  // never sent: it would plan and apply the whole cluster under a confirmation
  // that says nought nodes.
  const noNodes = targets.length === 0;
  const revision = declaration.revision;
  const stepIds = scope?.stepIds;

  const body = (): ConfigApplyRequest => ({
    revision,
    nodeIds: nodeIds?.length ? nodeIds : undefined,
    canaryNodeId: canary ?? undefined,
    removeUndeclared,
    acknowledgedHazards: [...acknowledged],
    expectedPlanHash: previewed?.plan.planHash ?? undefined,
    stepIds,
  });

  const preview = (then: (outcome: ConfigApplyOutcomeView, previousHash?: string) => void) => {
    const previousHash = previewed?.plan.planHash;
    setPlanError(null);
    apply.mutate(
      { body: { ...body(), acknowledgedHazards: [], expectedPlanHash: undefined }, dryRun: true, override: true },
      { onSuccess: (o) => then(o, previousHash), onError: (e) => setPlanError(e.message) },
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
  const targetKey = `${revision}:${targets.join(',')}:${canary ?? ''}:${removeUndeclared}:${(stepIds ?? []).join(',')}`;
  useEffect(() => {
    if (!opened || !declaration.declared) return;
    if (noNodes) {
      setPreviewed(null);
      setStage('plan');
      return;
    }
    runPlan();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, declaration.declared, targetKey]);

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

  const title = scope?.label ? `Review & apply ${scope.label}` : `Review & apply revision ${revision}`;
  const highHazards = previewed?.plan.hazards.filter((h) => h.hazardClass === 'HIGH') ?? [];
  const unacknowledged = highHazards.filter((h) => !acknowledged.has(h.id));
  const permissionGate = gateFor(
    can('config:apply', clusterId),
    APPLY_PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  const gate: GateVerdict =
    declaration.applyMode === 'CONFIG_MANAGED'
      ? { kind: 'blocked', reason: CONFIG_MANAGED_REASON }
      : permissionGate;

  const blockers: string[] = [];
  if (gate.kind === 'blocked') blockers.push(gate.reason);
  if (unacknowledged.length > 0) {
    blockers.push(
      `${unacknowledged.length} High hazard${unacknowledged.length === 1 ? '' : 's'} not yet acknowledged above.`,
    );
  }
  if (previewed?.overCap && !override) {
    blockers.push(
      `The plan has ${previewed.plan.stepCount} steps, over the cap of ${previewed.stepCap}. Override it above to continue.`,
    );
  }
  if (previewed && previewed.plan.stepCount === 0) blockers.push('Nothing to apply: every targeted node already matches.');
  const running = apply.isPending && apply.variables?.dryRun === false;
  const announcement =
    stage === 'result' && result
      ? result.outcome === 'APPLIED'
        ? 'Applied to every targeted node.'
        : result.outcome === 'HALTED'
          ? 'Halted partway: some nodes were written and others were not.'
          : 'The apply failed; nothing was written.'
      : running
        ? 'Applying, canary first.'
        : apply.isPending && apply.variables?.dryRun
          ? 'Planning the apply.'
          : noNodes
            ? 'No node is selected, so nothing is planned.'
            : previewed
              ? `Plan ready: ${previewed.plan.stepCount} step${previewed.plan.stepCount === 1 ? '' : 's'} on ${targets.length} node${targets.length === 1 ? '' : 's'}. Nothing has been written.`
              : '';
  const backups = declaration.nodes.filter((n) => !n.live);
  const nodeName = (id: string | null | undefined) => liveNodes.find((n) => n.nodeId === id)?.nodeName;

  return (
    <Drawer opened={opened} onClose={onClose} title={title} position="right" size="xl" padding="md">
      {/* Only the stage sentence is announced: a live region around the whole
          form re-reads the plan every time a checkbox moves, which buries the
          outcome it exists to carry. */}
      <VisuallyHidden aria-live="polite">{announcement}</VisuallyHidden>
      <div>
        <Stack gap="md">
          {scope?.label ? (
            <Text size="xs" c="dimmed">
              Scoped to {scope.label} on every targeted node. Everything else this cluster has pending stays pending.
            </Text>
          ) : null}

          {stage !== 'result' ? (
            <Stack gap="md">
              {apply.isPending && apply.variables?.dryRun ? <Text size="sm">Planning — reading every live node…</Text> : null}
              {!previewed && apply.isPending ? <Skeleton height={120} /> : null}
              {planError ? (
                <Alert color="red" variant="light" title="Could not plan" role="alert">
                  {planError} Fix the declaration and come back; nothing was changed.
                </Alert>
              ) : null}
              {moved ? (
                <Alert color="yellow" variant="light" title="The cluster moved — this is a new plan" role="alert">
                  Something changed on a node between the plan you were reading and the confirmation. The plan below has
                  been made again from what the nodes run now; review it, acknowledge its hazards, and continue. Nothing
                  was written.
                </Alert>
              ) : null}

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
                  data={liveNodes
                    .filter((n) => targets.includes(n.nodeId))
                    .map((n) => ({ value: n.nodeId, label: n.nodeName }))}
                  value={canary ?? previewed?.plan.canaryNodeId ?? null}
                  onChange={setCanary}
                  allowDeselect={false}
                  w={200}
                />
              </Group>

              {noNodes ? (
                <Alert color="yellow" variant="light" title="Select at least one node" role="alert">
                  No node is selected, so there is nothing to plan. Tick the nodes this apply should write to — an
                  empty selection is not a shortcut for all of them.
                </Alert>
              ) : null}
              {previewed ? (
                <>
                  <div className={classes.summaryBar}>
                    <Text size="xs">
                      <b>{previewed.plan.stepCount}</b> step{previewed.plan.stepCount === 1 ? '' : 's'} ·{' '}
                      {targets.length} node{targets.length === 1 ? '' : 's'} · canary{' '}
                      {nodeName(canary ?? previewed.plan.canaryNodeId) ?? 'first live node'}
                      {previewed.plan.hazards.length > 0
                        ? ` · ${previewed.plan.hazards.length} hazard${previewed.plan.hazards.length === 1 ? '' : 's'}, ${highHazards.length} High`
                        : ' · no hazards'}
                      {previewed.overCap ? ` · over the step cap of ${previewed.stepCap}` : ''}
                    </Text>
                    <Text
                      size="xs"
                      className={classes.state}
                      data-tone={unacknowledged.length > 0 ? 'warning' : undefined}
                    >
                      {unacknowledged.length > 0
                        ? `${unacknowledged.length} High hazard${unacknowledged.length === 1 ? '' : 's'} to acknowledge`
                        : 'nothing written yet'}
                    </Text>
                  </div>

                  {/* The step count, the nodes and the canary are in the summary
                      bar above; repeating them here only pushed the plan down. What
                      the bar cannot say is what happens to a node that is not live. */}
                  {backups.length > 0 ? (
                    <Text size="sm">
                      {backups.map((b) => b.nodeName).join(', ')}{' '}
                      {backups.length === 1 ? 'is a backup and' : 'are backups and'} will inherit through replication.
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

                  {previewed.plan.hazards.length > 0 ? (
                    <Stack gap="xs">
                      <Text size="sm" fw={600}>
                        Hazards ({previewed.plan.hazards.length}) — {highHazards.length} High
                      </Text>
                      {previewed.plan.hazards.map((h) => (
                        <div key={h.id} className={classes.hazard} data-class={h.hazardClass}>
                          <Text size="xs">
                            <Text component="span" size="xs" fw={600}>
                              {hazardClassWords(h.hazardClass)}
                            </Text>{' '}
                            · {h.nodeName} · {wireSectionLabel(h.section)} {h.key} — {h.message}
                          </Text>
                          {h.hazardClass === 'HIGH' ? (
                            <Checkbox
                              size="xs"
                              mt={4}
                              label={`I understand: ${h.kind.toLowerCase().replace(/_/g, ' ')} on ${h.nodeName}`}
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
                    </Stack>
                  ) : (
                    <Text size="xs" c="dimmed">
                      No hazards.
                    </Text>
                  )}

                  {previewed.plan.findings.length > 0 ? (
                    <Stack gap={2}>
                      <Text size="sm" fw={600}>
                        Noticed, not acted on
                      </Text>
                      {previewed.plan.findings.map((f, i) => (
                        <Text key={i} size="xs" c="dimmed">
                          {f.nodeName} · {wireSectionLabel(f.section)} {f.key ?? ''} — {f.detail}
                        </Text>
                      ))}
                    </Stack>
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

                  <ApplyResult outcome={previewed} />

                  {stage === 'plan' ? (
                    <Group align="center">
                      <Button
                        size="xs"
                        onClick={continueToConfirm}
                        loading={apply.isPending && apply.variables?.dryRun}
                        disabled={previewed.plan.stepCount === 0}
                      >
                        Continue to confirm
                      </Button>
                      {previewed.plan.stepCount === 0 ? (
                        <Text size="xs" c="dimmed">
                          Nothing to apply: every targeted node already matches revision {revision}.
                        </Text>
                      ) : null}
                      <Button
                        variant="default"
                        size="xs"
                        onClick={runPlan}
                        loading={apply.isPending && apply.variables?.dryRun}
                      >
                        Plan again
                      </Button>
                    </Group>
                  ) : null}
                </>
              ) : null}
            </Stack>
          ) : null}

          {stage === 'confirm' && previewed ? (
            <Stack gap="sm">
              <Text size="sm" fw={600}>
                Confirm
              </Text>
              <Text size="sm">
                {previewed.plan.stepCount} management write{previewed.plan.stepCount === 1 ? '' : 's'} on{' '}
                {targets.map((id) => nodeName(id) ?? id).join(', ')}, canary first, halting at the first failure.
                Nothing is rolled back if it halts; re-running converges. Steps that create queues or addresses destroy
                nothing; settings replace the broker's whole entry for their match.
              </Text>
              {blockers.length > 0 ? (
                <Stack gap={2}>
                  {blockers.map((b) => (
                    <Text key={b} size="xs" c="dimmed">
                      {b}
                    </Text>
                  ))}
                </Stack>
              ) : null}
              {apply.isError && apply.variables?.dryRun === false ? (
                <Alert color="red" variant="light" title={apply.error.title} role="alert">
                  {apply.error.message}
                  {apply.error.type.endsWith('plan-changed')
                    ? ' The cluster moved since this plan was made. Plan again and review it before confirming.'
                    : apply.error.type.endsWith('apply-in-progress')
                      ? ' Wait for it to finish, then plan again.'
                      : ''}
                </Alert>
              ) : null}
              {running ? <ApplyTimeline progress={progress} /> : null}
              <CapabilityGate verdict={gate}>
                <ConfirmByTyping
                  token={declaration.clusterName}
                  confirmLabel={`Apply to ${targets.length} node${targets.length === 1 ? '' : 's'}, canary first`}
                  loading={running}
                  disabled={blockers.length > 0}
                  onConfirm={run}
                />
              </CapabilityGate>
              {gate.kind === 'allowed' && gate.uncertain ? (
                <Text size="xs" c="dimmed">
                  Whether this connection may write has not been established yet; the broker will say if it refuses.
                </Text>
              ) : null}
              <Button variant="subtle" size="xs" px={0} onClick={() => setStage('plan')}>
                Back to the plan
              </Button>
            </Stack>
          ) : null}

          {stage === 'result' && result ? (
            <Stack gap="md">
              <ApplyResult outcome={result} clusterId={clusterId} />
              {result.outcome === 'HALTED' || result.outcome === 'FAILED' ? (
                <Text size="xs" c="dimmed">
                  Re-running the same revision converges: matching steps are already as declared, failed and
                  not-attempted ones are attempted again.
                </Text>
              ) : null}
              <Group>
                <Button size="xs" variant="default" onClick={runPlan}>
                  Plan again
                </Button>
                <Button size="xs" onClick={onClose}>
                  Back to the configuration
                </Button>
              </Group>
            </Stack>
          ) : null}
        </Stack>
      </div>
    </Drawer>
  );
}

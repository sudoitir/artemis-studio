import { useEffect, useMemo, useState } from 'react';
import { Alert, Anchor, Button, Checkbox, Group, Select, Skeleton, Stack, Switch, Text } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import {
  useApplyBrokerConfig,
  useBrokerConfig,
  useCluster,
  type ConfigApplyOutcomeView,
  type ConfigApplyRequest,
} from '../api/client.ts';
import { useCan } from '../auth/useCan.ts';
import { CapabilityGate } from '../shared/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../shared/capabilityGate.ts';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';
import { ApplyResult } from './ApplyResult.tsx';
import classes from './Configuration.module.css';
import { CONFIG_MANAGED_REASON, hazardClassWords, wireSectionLabel } from './words.ts';

export const APPLY_PERMISSION_LABEL = 'Apply declared configuration';

type Stage = 'plan' | 'confirm' | 'result';

/**
 * Plan → Confirm → Result (ADR-0067 D3, D4, D7).
 *
 * <p>The plan is a dry run, made on entry and again whenever the target set
 * changes. The real run names the plan hash it was shown, so a cluster that
 * moved between preview and apply is refused rather than acted on. High
 * hazards are acknowledged one by one — that is acknowledgement, not
 * confirmation; the confirmation is the cluster's name, typed.
 *
 * <p>Four outcomes are rendered: pending (the control is busy), applied,
 * halted (partial — the one that matters most), failed. The result uses the
 * same component as the plan, so the two can be compared row by row.
 */
export function ApplyView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const declaration = useBrokerConfig(clusterId);
  const cluster = useCluster(clusterId);
  const { can, loading } = useCan();
  const apply = useApplyBrokerConfig(clusterId);

  const [stage, setStage] = useState<Stage>('plan');
  const [plan, setPlan] = useState<ConfigApplyOutcomeView | null>(null);
  const [result, setResult] = useState<ConfigApplyOutcomeView | null>(null);
  const [nodeIds, setNodeIds] = useState<string[] | null>(null);
  const [canary, setCanary] = useState<string | null>(null);
  const [removeUndeclared, setRemoveUndeclared] = useState(false);
  const [acknowledged, setAcknowledged] = useState<Set<string>>(new Set());
  const [override, setOverride] = useState(false);
  const [planError, setPlanError] = useState<string | null>(null);

  const liveNodes = useMemo(() => (declaration.data?.nodes ?? []).filter((n) => n.live), [declaration.data]);
  const targets = nodeIds ?? liveNodes.map((n) => n.nodeId);
  const revision = declaration.data?.revision ?? 0;

  const request = (): ConfigApplyRequest => ({
    revision,
    nodeIds: nodeIds ?? undefined,
    canaryNodeId: canary ?? undefined,
    removeUndeclared,
    acknowledgedHazards: [...acknowledged],
    expectedPlanHash: plan?.plan.planHash ?? undefined,
  });

  const runPlan = () => {
    setPlanError(null);
    apply.mutate(
      { body: { ...request(), acknowledgedHazards: [], expectedPlanHash: undefined }, dryRun: true, override: true },
      {
        onSuccess: (o) => {
          setPlan(o);
          setAcknowledged(new Set());
          setResult(null);
          setStage('plan');
        },
        onError: (e) => setPlanError(e.message),
      },
    );
  };

  // Plan on entry, and again when the targets change. The declaration's own
  // refetches do not re-plan: the hash guards the real run instead.
  const targetKey = `${revision}:${targets.join(',')}:${canary ?? ''}:${removeUndeclared}`;
  useEffect(() => {
    if (!declaration.data?.declared) return;
    runPlan();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [declaration.data?.declared, targetKey]);

  const run = () =>
    apply.mutate(
      { body: request(), dryRun: false, override },
      {
        onSuccess: (o) => {
          setResult(o);
          setStage('result');
        },
      },
    );

  if (declaration.isError) {
    return (
      <Alert color="red" variant="light" title={declaration.error.title}>
        {declaration.error.message}
      </Alert>
    );
  }
  if (!declaration.data) return <Skeleton height={200} />;
  const d = declaration.data;
  if (!d.declared) {
    return (
      <Alert color="yellow" variant="light" title="Nothing is declared for this cluster">
        Declare the configuration first — adopt it from the running cluster, import broker.xml or add entries — then
        preview and apply it.{' '}
        <Anchor component={Link} to={`/clusters/${clusterId}/configuration`} size="sm">
          Back to Configuration
        </Anchor>
      </Alert>
    );
  }

  const highHazards = plan?.plan.hazards.filter((h) => h.hazardClass === 'HIGH') ?? [];
  const unacknowledged = highHazards.filter((h) => !acknowledged.has(h.id));
  const permissionGate = gateFor(
    can('config:apply', clusterId),
    APPLY_PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  const gate: GateVerdict =
    d.applyMode === 'CONFIG_MANAGED' ? { kind: 'blocked', reason: CONFIG_MANAGED_REASON } : permissionGate;

  const blockers: string[] = [];
  if (gate.kind === 'blocked') blockers.push(gate.reason);
  if (unacknowledged.length > 0) {
    blockers.push(
      `${unacknowledged.length} High hazard${unacknowledged.length === 1 ? '' : 's'} not yet acknowledged above.`,
    );
  }
  if (plan?.overCap && !override) {
    blockers.push(`The plan has ${plan.plan.stepCount} steps, over the cap of ${plan.stepCap}. Override it above to continue.`);
  }
  if (plan && plan.plan.stepCount === 0) blockers.push('Nothing to apply: every targeted node already matches.');
  const running = apply.isPending && apply.variables?.dryRun === false;
  const backups = d.nodes.filter((n) => !n.live);

  return (
    <Stack gap="md">
      <Group justify="space-between" align="baseline">
        <Text size="lg" fw={600}>
          Apply revision {d.revision} to {d.clusterName}
        </Text>
        <Anchor component={Link} to={`/clusters/${clusterId}/configuration`} size="xs">
          Back to Configuration
        </Anchor>
      </Group>

      <div className={classes.steps} aria-label="Progress">
        <span className={classes.step} data-current={stage === 'plan'}>
          1 Plan
        </span>
        <span className={classes.step} data-current={stage === 'confirm'}>
          2 Confirm
        </span>
        <span className={classes.step} data-current={stage === 'result'}>
          3 Result
        </span>
      </div>

      {/* Plan and result share one live region: a screen reader is told the
          outcome, not only the sighted operator. */}
      <div aria-live="polite">
        {stage !== 'result' ? (
          <Stack gap="md">
            {apply.isPending && apply.variables?.dryRun ? (
              <Text size="sm">Planning — reading every live node…</Text>
            ) : null}
            {planError ? (
              <Alert color="red" variant="light" title="Could not plan" role="alert">
                {planError} Fix the declaration and come back; nothing was changed.
              </Alert>
            ) : null}
            {plan ? (
              <>
                <Text size="sm">
                  {plan.plan.stepCount} step{plan.plan.stepCount === 1 ? '' : 's'} on {targets.length} live node
                  {targets.length === 1 ? '' : 's'}
                  {backups.length > 0
                    ? `; ${backups.map((b) => b.nodeName).join(', ')} ${backups.length === 1 ? 'is a backup and' : 'are backups and'} will inherit through replication`
                    : ''}
                  . Canary:{' '}
                  {liveNodes.find((n) => n.nodeId === (canary ?? plan.plan.canaryNodeId))?.nodeName ?? 'first live node'}.
                  Nothing has been written.
                </Text>

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
                    value={canary ?? plan.plan.canaryNodeId ?? null}
                    onChange={setCanary}
                    allowDeselect={false}
                    w={200}
                  />
                </Group>

                <Switch
                  label="Also remove settings and diverts Studio did not apply"
                  description="Off: only what Studio applied and is no longer declared is removed. On: undeclared items are removed too — if broker.xml also has one, the removal reverts on the next restart and Studio cannot tell. A High hazard per item."
                  size="xs"
                  checked={removeUndeclared}
                  onChange={(e) => setRemoveUndeclared(e.currentTarget.checked)}
                />

                {plan.plan.hazards.length > 0 ? (
                  <Stack gap="xs">
                    <Text size="sm" fw={600}>
                      Hazards ({plan.plan.hazards.length}) — {highHazards.length} High
                    </Text>
                    {plan.plan.hazards.map((h) => (
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

                {plan.plan.findings.length > 0 ? (
                  <Stack gap={2}>
                    <Text size="sm" fw={600}>
                      Noticed, not acted on
                    </Text>
                    {plan.plan.findings.map((f, i) => (
                      <Text key={i} size="xs" c="dimmed">
                        {f.nodeName} · {wireSectionLabel(f.section)} {f.key ?? ''} — {f.detail}
                      </Text>
                    ))}
                  </Stack>
                ) : null}

                {plan.overCap ? (
                  <Switch
                    label={`Override the step cap (${plan.plan.stepCount} steps, cap ${plan.stepCap})`}
                    description="The cap exists so one apply cannot rewrite a whole cluster by accident. Overriding is recorded in the audit event."
                    size="xs"
                    checked={override}
                    onChange={(e) => setOverride(e.currentTarget.checked)}
                  />
                ) : null}

                <ApplyResult outcome={plan} />

                {stage === 'plan' ? (
                  <Group align="center">
                    <Button size="xs" onClick={() => setStage('confirm')} disabled={plan.plan.stepCount === 0}>
                      Continue to confirm
                    </Button>
                    {plan.plan.stepCount === 0 ? (
                      <Text size="xs" c="dimmed">
                        Nothing to apply: every targeted node already matches revision {d.revision}.
                      </Text>
                    ) : null}
                    <Button variant="default" size="xs" onClick={runPlan} loading={apply.isPending && apply.variables?.dryRun}>
                      Plan again
                    </Button>
                  </Group>
                ) : null}
              </>
            ) : null}
          </Stack>
        ) : null}

        {stage === 'confirm' && plan ? (
          <Stack gap="sm" mt="md">
            <Text size="sm" fw={600}>
              Confirm
            </Text>
            <Text size="sm">
              {plan.plan.stepCount} management write{plan.plan.stepCount === 1 ? '' : 's'} on{' '}
              {targets.map((id) => liveNodes.find((n) => n.nodeId === id)?.nodeName ?? id).join(', ')}, canary first,
              halting at the first failure. Nothing is rolled back if it halts; re-running converges. Steps that create
              queues or addresses destroy nothing; settings replace the broker's whole entry for their match.
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
            <CapabilityGate verdict={gate}>
              <ConfirmByTyping
                token={d.clusterName}
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
                Re-running the same revision converges: matching steps are already as declared, failed and not-attempted
                ones are attempted again.
              </Text>
            ) : null}
            <Group>
              <Button size="xs" variant="default" onClick={runPlan}>
                Plan again
              </Button>
              <Anchor component={Link} to={`/clusters/${clusterId}/configuration?tab=drift`} size="xs">
                See the drift tab
              </Anchor>
            </Group>
          </Stack>
        ) : null}
      </div>
    </Stack>
  );
}

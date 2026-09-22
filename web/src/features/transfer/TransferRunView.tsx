import { useState } from 'react';
import { Alert, Anchor, Button, Code, Group, Modal, Progress, Skeleton, Stack, Text, Title } from '@mantine/core';
import { IconArrowRight } from '@tabler/icons-react';
import { Link, useParams } from '@tanstack/react-router';

import { useClusters } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { OutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { useTransferCommand, useTransferRun, type TransferRunView as Run } from './api.ts';
import classes from './TransferRunView.module.css';
import { ACTIVE, MODE, plural, stages, stateWords, toneColor } from './words.ts';

const n = (v: number) => v.toLocaleString();

/** The state as a sentence that says where the messages are and what the operator can do next. */
function sentence(run: Run): string {
  const held = stages(run) && run.held > 0 ? ` ${plural(run.held, 'message')} held in staging on ${run.source.nodeName}.` : '';
  switch (run.state) {
    case 'PREVIEWED':
      return 'Previewed and never started. Nothing was moved or sent.';
    case 'RUNNING':
      return `Running.${held}`;
    case 'WAITING_FOR_CAPACITY':
      return `Waiting for the target to have room, and continuing by itself once it does.${held}`;
    case 'RETURNING':
      return `Returning the held messages to ${run.source.queue}.${held}`;
    case 'SUCCEEDED':
      return `This run finished. Succeeded: every selected message was ${MODE[run.mode].past}.`;
    case 'PARTIAL':
      return `This run finished. Partial: ${plural(run.notTransferred, 'selected message')} could not be taken, because ${run.notTransferred === 1 ? 'it was' : 'they were'} being delivered to a consumer, scheduled, or already gone.`;
    case 'STOPPED':
      return `Stopped.${held} Resume it${stages(run) ? ', or return the held messages to the source' : ''}.`;
    case 'INTERRUPTED':
      return `Interrupted: Studio stopped while it ran.${held} Nothing is lost; resume it${stages(run) ? ' or return it' : ''}.`;
    case 'FAILED':
      return `This run failed.${held} Nothing held is lost; resume it once the cause is fixed${stages(run) ? ', or return the held messages' : ''}.`;
    case 'RETURNED':
      return `Returned: ${plural(run.returned, 'held message')} ${run.returned === 1 ? 'is' : 'are'} back on ${run.source.queue}.`;
  }
}

/**
 * How fast it is going, or — once it has finished — how fast it went. A finished run has no
 * pending rate, and saying "no rate yet" of one that ended minutes ago reads as a stall.
 */
function pace(run: Run, rate: number | null | undefined, left: string | null): string {
  if (ACTIVE.has(run.state)) {
    return rate
      ? `${rate.toLocaleString(undefined, { maximumFractionDigits: 1 })} messages a second${left ? `, ${left}` : ''}.`
      : 'No rate yet.';
  }
  const seconds =
    run.startedAt && run.finishedAt
      ? (Date.parse(run.finishedAt) - Date.parse(run.startedAt)) / 1000
      : null;
  if (run.delivered === 0 || seconds == null || seconds <= 0) {
    return `${plural(run.delivered, 'message')} ${MODE[run.mode].past}.`;
  }
  const perSecond = run.delivered / seconds;
  return `${plural(run.delivered, 'message')} ${MODE[run.mode].past} in ${plural(Math.max(1, Math.round(seconds)), 'second')}, ${perSecond.toLocaleString(undefined, { maximumFractionDigits: 1 })} a second.`;
}

function eta(run: Run): string | null {
  const rate = run.messagesPerSecond;
  if (!ACTIVE.has(run.state) || run.estimate == null || !rate) return null;
  const left = Math.max(0, run.estimate - run.delivered - run.notTransferred);
  const seconds = Math.ceil(left / rate);
  return seconds < 90 ? `about ${plural(seconds, 'second')} left` : `about ${plural(Math.ceil(seconds / 60), 'minute')} left`;
}

/**
 * One transfer run at its own address (ADR-0097): the pipeline from the selection through staging to
 * the target, the state in words, where each end stands, and the commands the state allows. Kept
 * live by the `transfer` stream topic, so a reload picks the run up where it is.
 */
export function TransferRunView() {
  useDisplayZone();
  const { clusterId, runId } = useParams({ strict: false }) as { clusterId: string; runId: string };
  const query = useTransferRun(clusterId, runId);
  const clusters = useClusters();
  const stop = useTransferCommand(clusterId, runId, 'stop');
  const resume = useTransferCommand(clusterId, runId, 'resume');
  const back = useTransferCommand(clusterId, runId, 'return');
  const { can, loading } = useCan();
  const [dialog, setDialog] = useState<'stop' | 'return' | null>(null);

  if (query.isError) {
    return (
      <Alert color="red" variant="light" title={query.error.title}>
        {query.error.message}
      </Alert>
    );
  }
  if (!query.data) return <Skeleton height={160} />;

  const run = query.data;
  const state = stateWords(run.state);
  const clusterName = (id: string) => clusters.data?.find((c) => c.id === id)?.name ?? 'another cluster';
  const staging = stages(run);
  const rate = run.messagesPerSecond;
  const left = eta(run);

  // The server re-checks every command; these only explain. Offered while grants load.
  const sourcePermission = run.mode === 'MOVE' ? 'message:move' : 'message:read';
  const sourceLabel = run.mode === 'MOVE' ? 'Move or retry messages' : 'Browse messages';
  const runGate = can(sourcePermission, run.source.clusterId)
    ? gateFor(can('message:send', run.target.clusterId), 'Send messages', undefined, loading)
    : gateFor(false, sourceLabel, undefined, loading);
  const returnGate = gateFor(can('message:move', run.source.clusterId), 'Move or retry messages', undefined, loading);
  const failure = [stop, resume, back].find((m) => m.isError)?.error ?? null;

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <Stack gap={2}>
          <Anchor component={Link} to={`/clusters/${clusterId}/transfers`} size="xs">
            ← All transfers
          </Anchor>
          <Title order={3}>
            {MODE[run.mode].verb} {run.estimate == null ? 'messages' : plural(run.estimate, 'message')}
          </Title>
          <Text size="sm">
            From {run.source.queue} on {run.source.nodeName} ({clusterName(run.source.clusterId)}) to {run.target.queue}{' '}
            on {run.target.nodeName} ({clusterName(run.target.clusterId)}).
          </Text>
          <Text size="xs" c="dimmed">
            By {run.username}
            {run.startedAt ? `, started ${absoluteLabel(run.startedAt)}` : ''}
            {`, selecting messages up to ${absoluteLabel(run.t0)}`}
            {run.overrideCap ? ', with the safety cap overridden' : ''}
          </Text>
        </Stack>
        <Group gap="xs">
          {run.auditEventId != null ? (
            <Anchor
              component={Link}
              to={`/clusters/${run.source.clusterId}/audit`}
              // The untyped router cannot type another feature's search; `BulkRunView` does the same.
              search={{ parentId: run.auditEventId } as never}
              size="sm"
            >
              Audit trail
            </Anchor>
          ) : null}
          {run.targetAuditEventId != null ? (
            <Anchor
              component={Link}
              to={`/clusters/${run.target.clusterId}/audit`}
              search={{ parentId: run.targetAuditEventId } as never}
              size="sm"
            >
              Target&rsquo;s audit trail
            </Anchor>
          ) : null}
          {ACTIVE.has(run.state) && run.state !== 'RETURNING' ? (
            <CapabilityGate verdict={runGate} what="stopping this transfer">
              <Button
                size="xs"
                variant="light"
                disabled={runGate.kind === 'blocked'}
                loading={stop.isPending}
                onClick={() => setDialog('stop')}
              >
                Stop
              </Button>
            </CapabilityGate>
          ) : null}
          {run.resumable ? (
            <CapabilityGate verdict={runGate} what="resuming this transfer">
              <Button
                size="xs"
                disabled={runGate.kind === 'blocked'}
                loading={resume.isPending}
                onClick={() => resume.mutate()}
              >
                Resume
              </Button>
            </CapabilityGate>
          ) : null}
          {run.returnable ? (
            <CapabilityGate verdict={returnGate} what="returning these messages">
              <Button
                size="xs"
                variant="light"
                color="red"
                disabled={returnGate.kind === 'blocked' || back.isPending}
                loading={back.isPending}
                onClick={() => setDialog('return')}
              >
                Return to source…
              </Button>
            </CapabilityGate>
          ) : null}
        </Group>
      </Group>

      {/* Only the state is announced; the figures change every batch and would drown it out. */}
      <div role="status" aria-live="polite" aria-label="Transfer state">
        <Text size="sm" fw={600} c={toneColor(state.tone)}>
          {sentence(run)}
        </Text>
      </div>
      {run.lastError ? (
        <Stack gap={4}>
          <Text size="sm">{run.lastError}</Text>
          {run.errorSnippet ? (
            <>
              <Text size="xs" fw={600}>
                Add this to <Code>broker.xml</Code>:
              </Text>
              <Code block>{run.errorSnippet}</Code>
            </>
          ) : null}
        </Stack>
      ) : null}

      {failure ? (
        <Alert color="red" variant="light" title={failure.title} role="alert">
          {failure.message} The run is as shown above; nothing further was done.
        </Alert>
      ) : null}

      <dl className={classes.strip} aria-label="Transfer pipeline">
        <div className={classes.stage}>
          <dt>Selected</dt>
          <dd>{run.estimate == null ? 'unknown' : n(run.estimate)}</dd>
        </div>
        <IconArrowRight className={classes.arrow} aria-hidden size={16} />
        <div className={classes.stage} data-tone={staging && run.held > 0 && !ACTIVE.has(run.state) ? 'warning' : undefined}>
          <dt>Held in staging</dt>
          <dd>{staging ? n(run.held) : 'not used'}</dd>
        </div>
        <IconArrowRight className={classes.arrow} aria-hidden size={16} />
        <div className={classes.stage}>
          <dt>Delivered</dt>
          <dd>{n(run.delivered)}</dd>
        </div>
        {run.notTransferred > 0 ? (
          <div className={classes.stage} data-tone="warning">
            <dt>Not transferred</dt>
            <dd>{n(run.notTransferred)}</dd>
          </div>
        ) : null}
        {run.expired > 0 ? (
          <div className={classes.stage}>
            <dt>Expired while held</dt>
            <dd>{n(run.expired)}</dd>
          </div>
        ) : null}
        {run.returned > 0 ? (
          <div className={classes.stage}>
            <dt>Returned to source</dt>
            <dd>{n(run.returned)}</dd>
          </div>
        ) : null}
      </dl>

      {/* The theme honours reduced motion, so the bar's transition drops out for those who ask. */}
      {run.estimate == null ? (
        <Text size="sm">How far along this is cannot be shown: the selection&rsquo;s size was not known at preview.</Text>
      ) : (
        <Progress
          aria-label="Messages delivered"
          value={run.estimate === 0 ? 100 : Math.min(100, ((run.delivered + run.notTransferred) / run.estimate) * 100)}
          color={toneColor(state.tone)}
        />
      )}
      <Text size="sm" className={classes.figures}>
        {pace(run, rate, left)}
      </Text>

      <OutcomeSummary
        verdict={state.text}
        verdictTone={state.tone}
        rows={[
          {
            key: 'source',
            name: `${run.source.nodeName}, source (${clusterName(run.source.clusterId)})`,
            count: n(run.mode === 'MOVE' ? run.staged : run.delivered),
            status: run.mode === 'MOVE' ? (staging ? 'taken off into staging' : 'moved by the broker') : 'read, left in place',
          },
          {
            key: 'target',
            name: `${run.target.nodeName}, target (${clusterName(run.target.clusterId)})`,
            count: n(run.delivered),
            status: 'delivered',
            tone: run.state === 'FAILED' ? 'danger' : undefined,
            detail: run.state === 'FAILED' ? run.lastError : null,
          },
        ]}
      />

      {run.notes.length > 0 ? (
        <Stack gap={4}>
          <Title order={5}>Good to know</Title>
          {run.notes.map((note) => (
            <Text key={note} size="sm">
              {note}
            </Text>
          ))}
        </Stack>
      ) : null}

      <Modal opened={dialog === 'stop'} onClose={() => setDialog(null)} title="Stop this transfer?">
        <Stack gap="sm">
          <Text size="sm">
            The batch in flight finishes, then the run stops. Nothing is lost: {staging ? 'held messages stay in staging, and ' : ''}
            the run can be resumed{staging ? ' or returned' : ''} later.
          </Text>
          <Group justify="flex-end">
            <Button size="xs" variant="default" onClick={() => setDialog(null)}>
              Keep running
            </Button>
            <Button size="xs" loading={stop.isPending} onClick={() => stop.mutate(undefined, { onSettled: () => setDialog(null) })}>
              Stop
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={dialog === 'return'} onClose={() => setDialog(null)} title="Return the held messages to the source?">
        <Stack gap="sm">
          <Text size="sm">
            Every message held in staging, {plural(run.held, 'message')} now, goes back on {run.source.queue} on{' '}
            {run.source.nodeName}, and the staging queue is removed. Messages already delivered to {run.target.queue} stay
            there. The run ends as returned and cannot be resumed.
          </Text>
          <ConfirmByTyping
            token={run.source.queue}
            label={`Type the source queue's name, "${run.source.queue}", to confirm`}
            confirmLabel={`Return ${plural(run.held, 'message')}`}
            loading={back.isPending}
            disabled={back.isPending}
            onConfirm={() => back.mutate(undefined, { onSettled: () => setDialog(null) })}
          />
        </Stack>
      </Modal>
    </Stack>
  );
}

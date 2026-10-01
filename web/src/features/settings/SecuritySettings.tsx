import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Button, Group, Progress, Stack, Text, VisuallyHidden } from '@mantine/core';

import { needsReauthentication } from '../../kernel/auth/api.ts';
import { useFreshSignIn } from '../../kernel/auth/freshSignIn.ts';
import { StepUp } from '../../kernel/auth/StepUp.tsx';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useSecretsStatus, useStartRotation, type RotationView, type SecretsStatus } from './api.ts';
import { keyVersionColumns } from './columns.ts';
import classes from './Settings.module.css';

const PROVIDERS: Record<string, string> = {
  env: 'Environment variable',
  file: 'File',
  vault: 'HashiCorp Vault',
  kubernetes: 'Kubernetes Secret',
};

const STATUSES: Record<string, string> = { RUNNING: 'Running', SUCCEEDED: 'Succeeded', FAILED: 'Failed' };

const ROTATE: ActionVerb = { verb: 'Start', past: 'Started', progressive: 'Starting' };

const when = (iso: string) => new Date(iso).toLocaleString();

/** The newer key version a rotation would move to, or null when the provider offers none. */
function targetVersion(s: SecretsStatus): number | null {
  const newest = Math.max(...s.availableVersions, s.currentVersion);
  return newest > s.currentVersion ? newest : null;
}

/** Secrets still wrapped under a version older than the current one. */
const straggling = (s: SecretsStatus) => Object.keys(s.countsByVersion).some((v) => Number(v) < s.currentVersion);

const secretNoun = (n: number) => (n === 1 ? 'secret' : 'secrets');

/** What a rotation would re-wrap: every secret still under a version below the goal. */
function rewrapCount(s: SecretsStatus, goal: number): number {
  return Object.entries(s.countsByVersion)
    .filter(([v]) => Number(v) < goal)
    .reduce((sum, [, n]) => sum + n, 0);
}

function rotationVerdict(missingPermission: boolean, running: boolean, rotatable: boolean) {
  if (missingPermission) return { kind: 'blocked', reason: 'You need the settings-write permission.' } as const;
  if (running) return { kind: 'blocked', reason: 'A rotation is running.' } as const;
  if (!rotatable) return { kind: 'blocked', reason: 'Add a newer key version to the provider first.' } as const;
  return { kind: 'allowed', uncertain: false } as const;
}

function announcement(starting: boolean, last: RotationView | null): string {
  if (starting) return 'Starting the rotation.';
  if (!last) return '';
  return `Key rotation ${(STATUSES[last.status] ?? last.status).toLowerCase()}.`;
}

/** The rotation mutation, its retry after a step-up, and the confirmation dialog's open state. */
function useRotationFlow() {
  const rotate = useStartRotation();
  const fresh = useFreshSignIn();
  const [open, setOpen] = useState(false);

  const { mutate, error: rotateError } = rotate;
  const start = useCallback(
    () =>
      mutate(undefined, {
        onSuccess: () => notify.succeeded({ action: ROTATE, subject: 'the key rotation' }),
        onError: (error) => {
          // A stale sign-in is answered by the prompt in the dialog, not by a failure.
          if (!needsReauthentication(error)) {
            notify.failed({
              action: ROTATE,
              subject: 'the key rotation',
              cause: error.message,
              next: nextAction(error.type),
            });
          }
        },
      }),
    [mutate],
  );

  // After a step-up the server's 403 is stale: retry once when the session turns fresh.
  const wasFresh = useRef(fresh);
  useEffect(() => {
    if (fresh && !wasFresh.current && needsReauthentication(rotateError)) start();
    wasFresh.current = fresh;
  }, [fresh, rotateError, start]);

  // Closes on success from either the first attempt or the retry after a step-up.
  useEffect(() => {
    if (rotate.isSuccess) setOpen(false);
  }, [rotate.isSuccess]);

  return { rotate, start, open, setOpen };
}

/**
 * The key provider, the key versions and the stored secrets under each, the last rotation, and the
 * control that starts one. Rotation re-wraps secrets in the background while Studio keeps serving,
 * so progress is shown and announced rather than awaited.
 */
export function SecuritySettings() {
  const status = useSecretsStatus();
  const { rotate, start, open, setOpen } = useRotationFlow();
  const { can, loading } = useCan();

  if (status.isError) {
    return <ErrorState error={status.error} onRetry={() => void status.refetch()} />;
  }
  // The frame holds the height of the provider, the versions and the rotation, so they do not push anything.
  if (status.isPending) return <LoadingState label="Loading key status" blockSize="28rem" />;

  const s = status.data;
  const last = s.lastRotation ?? null;
  const running = last?.status === 'RUNNING';
  const target = targetVersion(s);
  const rotatable = target !== null || straggling(s);
  const toRewrap = rewrapCount(s, target ?? s.currentVersion);
  const stored = Object.values(s.countsByVersion).reduce((a, b) => a + b, 0);
  const verdict = rotationVerdict(!loading && !can('settings:write'), running, rotatable);

  return (
    <Stack gap="lg" className={classes.narrow}>
      <VisuallyHidden role="status">{announcement(rotate.isPending, last)}</VisuallyHidden>

      <Section
        title="Key provider"
        headingLevel={3}
        variant="card"
        description="Studio reads its key versions from here; the keys themselves never reach Studio’s database or this screen."
      >
        <Text fw={600}>{PROVIDERS[s.provider] ?? s.provider}</Text>
      </Section>

      <MissingVersions s={s} />
      <Section title="Key versions" headingLevel={3}>
        <KeyVersions s={s} running={running} />
        {stored === 0 ? <Text size="sm">No secrets are stored yet.</Text> : null}
      </Section>

      <Text size="sm">{guidance(s, target, running, last?.status === 'SUCCEEDED')}</Text>
      <Group gap="sm">
        <CapabilityGate verdict={verdict} what="rotating the key">
          <Button disabled={verdict.kind === 'blocked'} loading={rotate.isPending} onClick={() => setOpen(true)}>
            Rotate key
          </Button>
        </CapabilityGate>
      </Group>

      {last ? (
        <RotationSummary rotation={last} />
      ) : (
        <Section title="No rotation has run" headingLevel={3} variant="card">
          <Text size="sm">
            Rotation re-wraps stored secrets under a newer key.{' '}
            {rotatable
              ? 'A newer key version is ready, so you can rotate now.'
              : 'It needs a newer key version in the provider; add one there, then rotate here.'}
          </Text>
        </Section>
      )}

      <RotateDialog
        open={open}
        onClose={() => setOpen(false)}
        explanation={rotateExplanation(s.currentVersion, target, toRewrap)}
        rotate={rotate}
        onConfirm={start}
      />
    </Stack>
  );
}

/** A version the provider has lost while secrets still depend on it: a fault, stated before anything else. */
function MissingVersions({ s }: Readonly<{ s: SecretsStatus }>) {
  const titleId = useId();
  if (s.missingVersions.length === 0) return null;
  return (
    <div className={classes.fault} role="alert" aria-labelledby={titleId}>
      <Text fw={600} id={titleId}>
        A key version is missing from the provider
      </Text>
      <Text size="sm">
        {s.missingVersions
          .map((v) => {
            const n = s.countsByVersion[String(v)] ?? 0;
            return `Version ${v} still protects ${n} stored ${secretNoun(n)}.`;
          })
          .join(' ')}{' '}
        Those secrets cannot be read until the key is restored to the provider. Restore it, then rotate so nothing
        depends on it.
      </Text>
    </div>
  );
}

function KeyVersions({ s, running }: Readonly<{ s: SecretsStatus; running: boolean }>) {
  const rows = [
    ...new Set([...s.availableVersions, ...Object.keys(s.countsByVersion).map(Number), s.currentVersion]),
  ].sort((x, y) => x - y);
  return (
    <DataTable
      variant="static"
      label="Key versions"
      columns={keyVersionColumns({
        state: (v) => versionState(s, v, running),
        stored: (v) => s.countsByVersion[String(v)] ?? 0,
      })}
      data={rows}
      rowKey={String}
      empty={null}
    />
  );
}

function rotateExplanation(currentVersion: number, target: number | null, toRewrap: number): string {
  if (target === null) {
    return `This finishes re-wrapping ${toRewrap} ${secretNoun(toRewrap)} still under older versions.`;
  }
  return `This re-wraps ${toRewrap} stored ${secretNoun(toRewrap)} from version ${currentVersion} to version ${target}.`;
}

function RotateDialog({
  open,
  onClose,
  explanation,
  rotate,
  onConfirm,
}: Readonly<{
  open: boolean;
  onClose: () => void;
  explanation: string;
  rotate: ReturnType<typeof useStartRotation>;
  onConfirm: () => void;
}>) {
  return (
    <ConfirmDialog
      opened={open}
      onClose={onClose}
      title="Rotate the key"
      confirmLabel="Rotate key"
      pending={rotate.isPending}
      consequence={
        <Stack gap="md">
          <Text size="sm">
            {explanation} Studio keeps serving meanwhile. Keep the old key in the provider until this succeeds.
          </Text>
          {needsReauthentication(rotate.error) ? (
            <StepUp returnTo={`${globalThis.location.pathname}?tab=settings-security`} />
          ) : null}
        </Stack>
      }
      onConfirm={onConfirm}
    />
  );
}

function versionState(s: SecretsStatus, v: number, running: boolean): string {
  const n = s.countsByVersion[String(v)] ?? 0;
  if (s.missingVersions.includes(v)) return 'Missing from the provider: restore it';
  if (v === s.currentVersion) return 'Current';
  if (v > s.currentVersion) return 'Newer, available to rotate to';
  if (n > 0) return `Older, still wraps ${n} ${secretNoun(n)}`;
  // A running rotation can still see a stale writer; removal is safe only once it has succeeded.
  return running
    ? 'Older, unused: keep until the rotation succeeds'
    : 'Older, unused: safe to remove from the provider';
}

/** What the operator does next, in the words of the state they are in. */
function guidance(s: SecretsStatus, target: number | null, running: boolean, succeeded: boolean): string {
  if (running)
    return 'A rotation is running. Studio keeps serving; keep the old key in the provider until it succeeds.';
  if (target !== null) return `Version ${target} is available. Rotate to re-wrap your stored secrets under it.`;
  if (straggling(s)) return 'Some secrets still use an older key. Rotate to finish moving them to the current version.';
  const removable = s.availableVersions.filter((v) => v < s.currentVersion && !s.countsByVersion[String(v)]);
  if (succeeded && removable.length > 0) {
    return `Version ${removable.join(', ')} protects no secrets and can be removed from the provider.`;
  }
  return 'Everything is on the current key. To rotate, first add a newer key version to the provider.';
}

function nextAction(type: string): string {
  if (type.endsWith('/no-newer-key')) return 'Add a newer key version to the provider, then try again.';
  if (type.endsWith('/rotation-running')) return 'Wait for the running rotation to finish.';
  return 'Try again; if it keeps failing, check the Studio log.';
}

function RotationSummary({ rotation: r }: Readonly<{ rotation: RotationView }>) {
  const counting = r.status === 'RUNNING' && r.rewrapped + r.remaining === 0;
  // Every row is re-wrapped, but the rotation waits out the settle window so no replica still writes under the old key.
  const settling = r.status === 'RUNNING' && !counting && r.remaining === 0;
  return (
    <Section title={`Last rotation: ${STATUSES[r.status] ?? r.status}`} headingLevel={3} variant="card">
      <Stack gap={2}>
        <Text size="sm" className={classes.figure}>
          Version {r.fromVersion} to version {r.toVersion}, started by {r.startedBy} at {when(r.startedAt)}.
        </Text>
        {r.status === 'RUNNING' && !counting ? (
          <Progress
            aria-label="Rotation progress"
            value={(100 * r.rewrapped) / (r.rewrapped + r.remaining)}
            size="md"
            my="xs"
          />
        ) : null}
        <Text size="sm" className={classes.figure}>
          {counting ? 'Progress: counting…' : `Progress: ${r.rewrapped} re-wrapped, ${r.remaining} remaining.`}
        </Text>
        {settling ? (
          <Text size="sm">
            Every secret is re-wrapped. Studio is confirming that no replica still writes under the old key; this
            finishes within a minute.
          </Text>
        ) : null}
        {r.status === 'RUNNING' ? <Text size="sm">Running for {elapsed(r.startedAt)}.</Text> : null}
        {r.finishedAt ? <Text size="sm">Finished at {when(r.finishedAt)}.</Text> : null}
        {r.status === 'FAILED' ? (
          <Group gap="xs" align="flex-start" wrap="nowrap" role="alert">
            <StatusBadge tone="danger">Failed</StatusBadge>
            <Text size="sm">
              {r.error ?? 'No cause was recorded.'} Keep the old key in the provider, fix the cause, and rotate again.
            </Text>
          </Group>
        ) : null}
      </Stack>
    </Section>
  );
}

function elapsed(startedAt: string): string {
  const secs = Math.max(0, Math.round((Date.now() - new Date(startedAt).getTime()) / 1000));
  return secs < 60 ? `${secs} s` : `${Math.floor(secs / 60)} min ${secs % 60} s`;
}

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Group, Modal, Paper, Progress, Skeleton, Stack, Table, Text } from '@mantine/core';

import { needsReauthentication } from '../../kernel/auth/api.ts';
import { useFreshSignIn } from '../../kernel/auth/freshSignIn.ts';
import { StepUp } from '../../kernel/auth/StepUp.tsx';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { useSecretsStatus, useStartRotation, type RotationView, type SecretsStatus } from './api.ts';

const PROVIDERS: Record<string, string> = {
  env: 'Environment variable',
  file: 'File',
  vault: 'HashiCorp Vault',
  kubernetes: 'Kubernetes Secret',
};

const STATUSES: Record<string, string> = { RUNNING: 'Running', SUCCEEDED: 'Succeeded', FAILED: 'Failed' };

const numeric = { fontVariantNumeric: 'tabular-nums' } as const;

const when = (iso: string) => new Date(iso).toLocaleString();

/** The newer key version a rotation would move to, or null when the provider offers none. */
function targetVersion(s: SecretsStatus): number | null {
  const newest = Math.max(...s.availableVersions, s.currentVersion);
  return newest > s.currentVersion ? newest : null;
}

/** Secrets still wrapped under a version older than the current one. */
const straggling = (s: SecretsStatus) => Object.keys(s.countsByVersion).some((v) => Number(v) < s.currentVersion);

/**
 * The key provider, the key versions and the stored secrets under each, the last rotation, and the
 * control that starts one. Rotation re-wraps secrets in the background while Studio keeps serving,
 * so progress is shown and announced rather than awaited.
 */
export function SecuritySettings() {
  const status = useSecretsStatus();
  const rotate = useStartRotation();
  const fresh = useFreshSignIn();
  const { can, loading } = useCan();
  const [open, setOpen] = useState(false);

  // After a step-up the server's 403 is stale: retry once when the session turns fresh.
  const wasFresh = useRef(fresh);
  useEffect(() => {
    if (fresh && !wasFresh.current && needsReauthentication(rotate.error)) rotate.mutate();
    wasFresh.current = fresh;
  }, [fresh, rotate]);

  // Closes on success from either the first attempt or the retry after a step-up.
  useEffect(() => {
    if (rotate.isSuccess) setOpen(false);
  }, [rotate.isSuccess]);

  if (status.isError) {
    return (
      <Alert color="red" variant="light" title={status.error.title} role="alert">
        <Stack gap="xs" align="flex-start">
          <Text size="sm">{status.error.message} The key status could not be loaded; try again.</Text>
          <Button size="xs" variant="default" onClick={() => status.refetch()}>
            Retry
          </Button>
        </Stack>
      </Alert>
    );
  }
  if (status.isPending)
    return (
      <Stack gap="sm" maw={640} aria-busy="true" aria-label="Loading key status">
        <Skeleton height={72} />
        <Skeleton height={96} />
      </Stack>
    );

  const s = status.data;
  const last = s.lastRotation ?? null;
  const running = last?.status === 'RUNNING';
  const target = targetVersion(s);
  const rotatable = target !== null || straggling(s);
  // What a rotation would re-wrap: every secret still under a version below the target.
  const goal = target ?? s.currentVersion;
  const toRewrap = Object.entries(s.countsByVersion)
    .filter(([v]) => Number(v) < goal)
    .reduce((sum, [, n]) => sum + n, 0);
  const stored = Object.values(s.countsByVersion).reduce((a, b) => a + b, 0);
  const verdict =
    !loading && !can('settings:write')
      ? ({ kind: 'blocked', reason: 'You need the settings-write permission.' } as const)
      : running
        ? ({ kind: 'blocked', reason: 'A rotation is running.' } as const)
        : !rotatable
          ? ({ kind: 'blocked', reason: 'Add a newer key version to the provider first.' } as const)
          : ({ kind: 'allowed', uncertain: false } as const);

  const rows = [
    ...new Set([...s.availableVersions, ...Object.keys(s.countsByVersion).map(Number), s.currentVersion]),
  ].sort((x, y) => x - y);
  const conflict = rotate.error && !needsReauthentication(rotate.error) ? rotate.error : null;
  const outcome = rotate.isPending
    ? 'Starting the rotation.'
    : last
      ? `Key rotation ${STATUSES[last.status]?.toLowerCase() ?? last.status.toLowerCase()}.`
      : '';

  return (
    <Stack gap="md" maw={640}>
      <div role="status" aria-live="polite" style={{ position: 'absolute', insetInlineStart: -9999 }}>
        {outcome}
      </div>
      <Paper withBorder p="md">
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          Key provider
        </Text>
        <Text fw={600}>{PROVIDERS[s.provider] ?? s.provider}</Text>
        <Text size="sm" c="dimmed">
          Studio reads its key versions from here; the keys themselves never reach Studio&rsquo;s database or this
          screen.
        </Text>
      </Paper>

      {s.missingVersions.length > 0 ? (
        <Alert color="red" variant="light" role="alert" title="A key version is missing from the provider">
          {s.missingVersions
            .map((v) => {
              const n = s.countsByVersion[String(v)] ?? 0;
              return `Version ${v} still protects ${n} stored ${n === 1 ? 'secret' : 'secrets'}.`;
            })
            .join(' ')}{' '}
          Those secrets cannot be read until the key is restored to the provider. Restore it, then rotate so nothing
          depends on it.
        </Alert>
      ) : null}

      <Table aria-label="Key versions">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Key version</Table.Th>
            <Table.Th>State</Table.Th>
            <Table.Th ta="end">Stored secrets</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((v) => (
            <Table.Tr key={v}>
              <Table.Td style={numeric}>{v}</Table.Td>
              <Table.Td>{versionState(s, v, running)}</Table.Td>
              <Table.Td ta="end" style={numeric}>
                {s.countsByVersion[String(v)] ?? 0}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {stored === 0 ? <Text size="sm">No secrets are stored yet.</Text> : null}

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
        <Alert variant="light" color="gray" title="No rotation has run">
          Rotation re-wraps stored secrets under a newer key.{' '}
          {rotatable
            ? 'A newer key version is ready, so you can rotate now.'
            : 'It needs a newer key version in the provider; add one there, then rotate here.'}
        </Alert>
      )}

      <Modal opened={open} onClose={() => setOpen(false)} title="Rotate the key">
        <Stack gap="md">
          <Text size="sm">
            {target !== null
              ? `This re-wraps ${toRewrap} stored ${toRewrap === 1 ? 'secret' : 'secrets'} from version ${s.currentVersion} to version ${target}.`
              : `This finishes re-wrapping ${toRewrap} ${toRewrap === 1 ? 'secret' : 'secrets'} still under older versions.`}{' '}
            Studio keeps serving meanwhile. Keep the old key in the provider until this succeeds.
          </Text>
          {needsReauthentication(rotate.error) ? (
            <StepUp returnTo={`${window.location.pathname}?tab=settings-security`} />
          ) : null}
          {conflict ? (
            <Alert color="red" variant="light" role="alert" title="Not started">
              {conflict.message} {nextAction(conflict.type)}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button loading={rotate.isPending} onClick={() => rotate.mutate()}>
              Rotate key
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function versionState(s: SecretsStatus, v: number, running: boolean): string {
  const n = s.countsByVersion[String(v)] ?? 0;
  if (s.missingVersions.includes(v)) return 'Missing from the provider: restore it';
  if (v === s.currentVersion) return 'Current';
  if (v > s.currentVersion) return 'Newer, available to rotate to';
  if (n > 0) return `Older, still wraps ${n} ${n === 1 ? 'secret' : 'secrets'}`;
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

function RotationSummary({ rotation: r }: { rotation: RotationView }) {
  const counting = r.status === 'RUNNING' && r.rewrapped + r.remaining === 0;
  // Every row is re-wrapped, but the rotation waits out the settle window so no replica still writes under the old key.
  const settling = r.status === 'RUNNING' && !counting && r.remaining === 0;
  return (
    <Alert
      variant="light"
      color={r.status === 'FAILED' ? 'red' : r.status === 'RUNNING' ? 'blue' : 'gray'}
      title={`Last rotation: ${STATUSES[r.status] ?? r.status}`}
    >
      <Stack gap={2}>
        <Text size="sm" style={numeric}>
          Version {r.fromVersion} to version {r.toVersion}, started by {r.startedBy} at {when(r.startedAt)}.
        </Text>
        {r.status === 'RUNNING' && !counting ? (
          <Progress
            aria-label="Rotation progress"
            value={(100 * r.rewrapped) / (r.rewrapped + r.remaining)}
            color="blue"
            size="md"
            animated
            my={4}
          />
        ) : null}
        <Text size="sm" style={numeric}>
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
          <Text size="sm" role="alert">
            {r.error ?? 'No cause was recorded.'} Keep the old key in the provider, fix the cause, and rotate again.
          </Text>
        ) : null}
      </Stack>
    </Alert>
  );
}

function elapsed(startedAt: string): string {
  const secs = Math.max(0, Math.round((Date.now() - new Date(startedAt).getTime()) / 1000));
  return secs < 60 ? `${secs} s` : `${Math.floor(secs / 60)} min ${secs % 60} s`;
}

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Group, Loader, Modal, Stack, Table, Text } from '@mantine/core';

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
  if (status.isPending) return <Loader size="sm" />;

  const s = status.data;
  const last = s.lastRotation ?? null;
  const running = last?.status === 'RUNNING';
  const target = targetVersion(s);
  const rotatable = target !== null || straggling(s);
  const stored = Object.values(s.countsByVersion).reduce((a, b) => a + b, 0);
  const verdict =
    !loading && !can('settings:write')
      ? ({ kind: 'blocked', reason: 'You need the settings-write permission.' } as const)
      : running
        ? ({ kind: 'blocked', reason: 'A rotation is running.' } as const)
        : !rotatable
          ? ({ kind: 'blocked', reason: 'Add a newer key version to the provider first.' } as const)
          : ({ kind: 'allowed', uncertain: false } as const);

  const versions = Object.entries(s.countsByVersion)
    .map(([v, n]) => [Number(v), n] as const)
    .sort(([a], [b]) => a - b);
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
      <Table withRowBorders={false}>
        <Table.Tbody>
          <Table.Tr>
            <Table.Th scope="row">Key provider</Table.Th>
            <Table.Td>{PROVIDERS[s.provider] ?? s.provider}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th scope="row">Current key version</Table.Th>
            <Table.Td style={numeric}>{s.currentVersion}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th scope="row">Available versions</Table.Th>
            <Table.Td style={numeric}>{s.availableVersions.join(', ') || 'none'}</Table.Td>
          </Table.Tr>
        </Table.Tbody>
      </Table>

      <Table aria-label="Stored secrets per key version">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Key version</Table.Th>
            <Table.Th ta="end">Stored secrets</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {versions.length === 0 ? (
            <Table.Tr>
              <Table.Td colSpan={2}>No secrets are stored yet.</Table.Td>
            </Table.Tr>
          ) : (
            versions.map(([v, n]) => (
              <Table.Tr key={v}>
                <Table.Td style={numeric}>{v}</Table.Td>
                <Table.Td ta="end" style={numeric}>
                  {n}
                </Table.Td>
              </Table.Tr>
            ))
          )}
        </Table.Tbody>
      </Table>

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
        <Alert variant="light" title="No rotation has run">
          Rotation re-wraps stored secrets under a newer key. It needs a newer key version in the provider; add one
          there, then rotate here.
        </Alert>
      )}

      <Modal opened={open} onClose={() => setOpen(false)} title="Rotate the key">
        <Stack gap="md">
          <Text size="sm">
            This re-wraps {stored} stored {stored === 1 ? 'secret' : 'secrets'} from version {s.currentVersion} to
            version {target ?? s.currentVersion}. Studio keeps serving meanwhile. Keep the old key in the provider until
            this succeeds.
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
            <Button
              loading={rotate.isPending}
              onClick={() => rotate.mutate(undefined, { onSuccess: () => setOpen(false) })}
            >
              Rotate key
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function nextAction(type: string): string {
  if (type.endsWith('/no-newer-key')) return 'Add a newer key version to the provider, then try again.';
  if (type.endsWith('/rotation-running')) return 'Wait for the running rotation to finish.';
  return 'Try again; if it keeps failing, check the Studio log.';
}

function RotationSummary({ rotation: r }: { rotation: RotationView }) {
  const counting = r.status === 'RUNNING' && r.rewrapped + r.remaining === 0;
  return (
    <Alert
      variant="light"
      color={r.status === 'FAILED' ? 'red' : undefined}
      title={`Last rotation: ${STATUSES[r.status] ?? r.status}`}
    >
      <Stack gap={2}>
        <Text size="sm" style={numeric}>
          Version {r.fromVersion} to version {r.toVersion}, started by {r.startedBy} at {when(r.startedAt)}.
        </Text>
        <Text size="sm" style={numeric}>
          {counting ? 'Progress: counting…' : `Progress: ${r.rewrapped} re-wrapped, ${r.remaining} remaining.`}
        </Text>
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

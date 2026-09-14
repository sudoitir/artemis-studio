import { useState } from 'react';
import { Alert, Button, Group, SegmentedControl, Stack, Table, Text, VisuallyHidden } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDecideFinding, useFindings, type FindingView } from './api.ts';

const STATUSES = [
  { value: 'OPEN', label: 'Open' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'DISMISSED', label: 'Dismissed' },
  { value: 'ALL', label: 'All' },
];

const WRITE_REASON = 'Confirming or dismissing a finding needs the governance:write permission.';

function field(f: FindingView): string {
  const where = f.location === 'BODY' ? 'body' : f.location === 'HEADER' ? 'header' : 'property';
  return f.fieldPath ? `${where} ${f.fieldPath}` : where;
}

/**
 * The classification inbox (data-governance spec): personal data the detectors found in fields no rule covers.
 * Every such value is already masked; a decision here only turns the detection into a rule or an exception.
 */
export function FindingsInbox() {
  const [status, setStatus] = useState('OPEN');
  const findings = useFindings(status);
  const decide = useDecideFinding();
  const { can, loading } = useCan();
  // While grants load, offer the controls: the server decides (operator-ui spec).
  const canWrite = loading || can('governance:write');
  const [pending, setPending] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [failure, setFailure] = useState<string | null>(null);

  function act(f: FindingView, decision: 'confirm' | 'dismiss') {
    setFailure(null);
    setPending(f.id);
    decide.mutate(
      { findingId: f.id, decision },
      {
        onSuccess: () =>
          setAnnouncement(
            decision === 'confirm'
              ? `Confirmed: a rule now masks ${field(f)} on ${f.address} as ${f.dataClassLabel}.`
              : `Dismissed: ${field(f)} on ${f.address} is no longer masked as ${f.dataClassLabel}.`,
          ),
        onError: (e) => {
          setFailure(`The finding for ${field(f)} on ${f.address} was not ${decision}ed: ${e.message} Try again.`);
          setAnnouncement(`The finding for ${field(f)} was not ${decision}ed.`);
        },
        onSettled: () => setPending(null),
      },
    );
  }

  return (
    <Stack gap="md">
      <VisuallyHidden>
        <div role="status" aria-live="polite">
          {announcement}
        </div>
      </VisuallyHidden>

      <Stack gap={4}>
        <Text size="sm">
          A finding is personal data the detectors recognised in a field no masking rule names. Those values are
          already masked. Confirm a finding to make it a rule, or dismiss it as a false positive so that field is no
          longer masked for that class.
        </Text>
        {canWrite ? null : (
          <Text size="sm" c="dimmed">
            {WRITE_REASON}
          </Text>
        )}
      </Stack>

      <SegmentedControl
        size="xs"
        data={STATUSES}
        value={status}
        onChange={setStatus}
        aria-label="Show findings by status"
        style={{ alignSelf: 'flex-start' }}
      />

      {failure ? (
        <Alert variant="light" color="red" title="The decision did not apply" withCloseButton onClose={() => setFailure(null)}>
          {failure}
        </Alert>
      ) : null}

      {findings.isPending ? (
        <Text size="sm">Loading findings…</Text>
      ) : findings.isError ? (
        <Alert variant="light" color="red" title="Findings could not be loaded">
          <Stack gap="xs">
            <Text size="sm">{findings.error.message}</Text>
            <Group>
              <Button size="xs" variant="default" onClick={() => findings.refetch()}>
                Try again
              </Button>
            </Group>
          </Stack>
        </Alert>
      ) : findings.data.length === 0 ? (
        status === 'OPEN' || status === 'ALL' ? (
          <Text size="sm">
            No personal data has been detected in a field that no rule covers. Findings appear here as Studio reads
            messages.
          </Text>
        ) : (
          <Stack gap="xs" align="flex-start">
            <Text size="sm">No {status.toLowerCase()} findings. Other findings may exist under another status.</Text>
            <Button size="xs" variant="default" onClick={() => setStatus('ALL')}>
              Show all findings
            </Button>
          </Stack>
        )
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Field</Table.Th>
              <Table.Th>Address</Table.Th>
              <Table.Th>Class</Table.Th>
              <Table.Th style={{ textAlign: 'end' }}>Seen</Table.Th>
              <Table.Th>Last seen</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>
                <VisuallyHidden>Decision</VisuallyHidden>
              </Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {findings.data.map((f) => (
              <Table.Tr key={f.id}>
                <Table.Td>
                  <Text size="sm" ff="monospace">
                    {field(f)}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" ff="monospace">
                    {f.address}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{f.dataClassLabel}</Text>
                </Table.Td>
                <Table.Td style={{ textAlign: 'end', fontVariantNumeric: 'tabular-nums' }}>
                  <Text size="sm">{f.hitCount.toLocaleString()}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{absoluteLabel(Date.parse(f.lastSeenAt))}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{STATUSES.find((s) => s.value === f.status)?.label ?? f.status}</Text>
                </Table.Td>
                <Table.Td>
                  {f.status === 'OPEN' ? (
                    <Group gap={4} wrap="nowrap">
                      <Button
                        size="compact-xs"
                        variant="default"
                        disabled={!canWrite || (pending !== null && pending !== f.id)}
                        loading={pending === f.id && decide.variables?.decision === 'confirm'}
                        onClick={() => act(f, 'confirm')}
                        aria-label={`Confirm ${field(f)} on ${f.address} as ${f.dataClassLabel}`}
                      >
                        Confirm
                      </Button>
                      <Button
                        size="compact-xs"
                        variant="default"
                        disabled={!canWrite || (pending !== null && pending !== f.id)}
                        loading={pending === f.id && decide.variables?.decision === 'dismiss'}
                        onClick={() => act(f, 'dismiss')}
                        aria-label={`Dismiss ${field(f)} on ${f.address} as not ${f.dataClassLabel}`}
                      >
                        Dismiss
                      </Button>
                    </Group>
                  ) : null}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

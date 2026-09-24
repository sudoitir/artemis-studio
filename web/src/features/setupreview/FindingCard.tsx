import {
  Anchor,
  Badge,
  Button,
  Code,
  CopyButton,
  Group,
  Paper,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { useRevokeRisk, type SetupFindingView } from './api.ts';
import { SEVERITY_MEANING, SEVERITY_WORDS } from './words.ts';

/** Emphasis only where something is wrong; the word carries the meaning. */
function severityColor(severity: string): string {
  return severity === 'CRITICAL' ? 'red' : severity === 'WARNING' ? 'yellow' : 'gray';
}

/**
 * One finding: what is wrong, what it costs, what each node reported, and the fix to
 * copy. An accepted risk stays visible with who accepted it and why.
 */
export function FindingCard({
  finding: f,
  clusterId,
  canAccept,
  onAccept,
  announce,
}: {
  finding: SetupFindingView;
  clusterId: string;
  canAccept: boolean;
  onAccept: () => void;
  announce: (message: string) => void;
}) {
  const revoke = useRevokeRisk(clusterId);
  const accepted = f.acceptance?.active ? f.acceptance : null;
  const expired = f.acceptance && !f.acceptance.active ? f.acceptance : null;
  const acceptReason = 'Accepting a risk silences its alert, so it needs alert:write on this cluster.';

  return (
    <Paper withBorder p="sm" component="article" aria-label={f.title}>
      <Stack gap="xs">
        <Group gap="xs" wrap="nowrap" align="flex-start">
          <Badge
            color={severityColor(f.severity)}
            variant={f.severity === 'INFO' ? 'outline' : 'light'}
            title={SEVERITY_MEANING[f.severity]}
          >
            {SEVERITY_WORDS[f.severity] ?? f.severity}
          </Badge>
          <Stack gap={0} style={{ flex: 1 }}>
            <Text fw={600} size="sm">
              {f.title}
            </Text>
            <Text size="xs" c="dimmed">
              {f.subject === 'cluster' ? 'Whole cluster' : `Node ${f.subjectLabel}`} · {f.code} · first seen{' '}
              {absoluteLabel(f.firstSeenAt)}
              {f.stale ? ' · not re-checked by the last review: its node did not answer' : ''}
            </Text>
          </Stack>
        </Group>

        {accepted ? (
          <Text size="sm">
            <Text span fw={600}>
              Accepted as a known risk
            </Text>{' '}
            by {accepted.acceptedBy} on {absoluteLabel(accepted.createdAt)}
            {accepted.expiresAt ? `, until ${absoluteLabel(accepted.expiresAt)}` : ', until revoked'}: “{accepted.reason}”
          </Text>
        ) : null}
        {expired ? (
          <Text size="sm" c="var(--as-warning)">
            An acceptance by {expired.acceptedBy} expired on {absoluteLabel(expired.expiresAt)}; the finding is open again.
          </Text>
        ) : null}

        <Text size="sm">{f.impact}</Text>

        {f.evidence.length > 0 ? (
          <Table withTableBorder withColumnBorders fz="xs" aria-label={`Evidence for ${f.code}`}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Node</Table.Th>
                <Table.Th>Reported</Table.Th>
                <Table.Th>Value</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {f.evidence.map((e, i) => (
                <Table.Tr key={`${e.node}|${e.key}|${i}`}>
                  <Table.Td>{e.node ?? 'cluster'}</Table.Td>
                  <Table.Td>{e.key}</Table.Td>
                  <Table.Td style={{ fontFamily: 'var(--mantine-font-family-monospace)', fontVariantNumeric: 'tabular-nums' }}>
                    {e.value ?? '—'}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        ) : null}

        <Text size="sm">
          <Text span fw={600}>
            Fix:
          </Text>{' '}
          {f.recommendation}
        </Text>
        {f.snippet ? (
          <Stack gap={4}>
            <Group justify="space-between">
              <Text size="xs" c="dimmed">
                broker.xml
              </Text>
              <CopyButton value={f.snippet} timeout={1500}>
                {({ copied, copy }) => (
                  <Button size="compact-xs" variant="subtle" onClick={copy} aria-label={`Copy the broker.xml fix for ${f.code}`}>
                    {copied ? 'Copied' : 'Copy'}
                  </Button>
                )}
              </CopyButton>
            </Group>
            <Code block style={{ fontSize: 11, whiteSpace: 'pre-wrap' }}>
              {f.snippet}
            </Code>
          </Stack>
        ) : null}
        {f.caveats.length > 0 ? (
          <Stack gap={2}>
            {f.caveats.map((c) => (
              <Text size="xs" c="dimmed" key={c}>
                Note: {c}
              </Text>
            ))}
          </Stack>
        ) : null}

        <Group gap="xs">
          {f.appliable ? (
            <Anchor component={Link} to={`/clusters/${clusterId}/configuration`} size="sm">
              Apply it in Broker configuration
            </Anchor>
          ) : null}
          {accepted ? (
            <Button
              size="compact-sm"
              variant="default"
              disabled={!canAccept}
              title={canAccept ? undefined : acceptReason}
              loading={revoke.isPending}
              onClick={() =>
                revoke.mutate(
                  { code: f.code, subject: f.subject },
                  {
                    onSuccess: () => announce(`Acceptance revoked; ${f.code} is open again.`),
                    onError: (e) => announce(`The acceptance was not revoked: ${e.message}`),
                  },
                )
              }
            >
              Revoke acceptance
            </Button>
          ) : (
            <Button
              size="compact-sm"
              variant="default"
              disabled={!canAccept}
              title={canAccept ? undefined : acceptReason}
              onClick={onAccept}
            >
              Accept as a known risk…
            </Button>
          )}
        </Group>
        {!canAccept ? (
          <Text size="xs" c="dimmed">
            {acceptReason}
          </Text>
        ) : null}
      </Stack>
    </Paper>
  );
}

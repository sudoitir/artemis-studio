import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  CopyButton,
  Group,
  SegmentedControl,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { IconCopy } from '@tabler/icons-react';

import { useTokenUsage, type TokenView, type UsagePeriod } from './api.ts';
import { serverNow } from '../../kernel/time/time.ts';

const NUMERIC = { fontVariantNumeric: 'tabular-nums' } as const;

/** Revoked, expired or active, in words; colour only where something is wrong. */
export function TokenStatus({ token }: { token: TokenView }) {
  if (token.revokedAt) {
    return (
      <Badge size="xs" color="red" variant="light">
        revoked
      </Badge>
    );
  }
  if (Date.parse(token.expiresAt) < serverNow()) {
    return (
      <Badge size="xs" color="red" variant="light">
        expired
      </Badge>
    );
  }
  return (
    <Badge size="xs" color="gray" variant="light">
      active
    </Badge>
  );
}

/** A secret disclosed once, at minting or rotation, with a copy control. */
export function OneTimeSecret({ value, note }: { value: string; note?: string }) {
  return (
    <Stack gap="sm">
      <Alert color="yellow">This value is shown once. Copy it now — it cannot be retrieved again.</Alert>
      {note ? <Text size="sm">{note}</Text> : null}
      <Group>
        <TextInput value={value} readOnly style={{ flex: 1 }} ff="monospace" aria-label="API key" />
        <CopyButton value={value}>
          {({ copy, copied }) => (
            <ActionIcon onClick={copy} aria-label={copied ? 'Copied' : 'Copy key'}>
              <IconCopy size={16} />
            </ActionIcon>
          )}
        </CopyButton>
      </Group>
    </Stack>
  );
}

/** One token's requests per day over the chosen period, with denials, limits and errors. */
export function TokenUsagePanel({ scope, tokenId }: { scope: 'own' | 'admin'; tokenId: string }) {
  const [days, setDays] = useState<UsagePeriod>(7);
  const usage = useTokenUsage(scope, tokenId, days);

  return (
    <Stack gap="sm">
      <SegmentedControl
        size="xs"
        aria-label="Period"
        value={String(days)}
        onChange={(v) => setDays(Number(v) as UsagePeriod)}
        data={[
          { value: '1', label: 'Today' },
          { value: '7', label: '7 days' },
          { value: '30', label: '30 days' },
        ]}
      />
      {usage.isError ? (
        <Alert color="red" title="Usage could not be loaded">
          {usage.error.message} Try again, or check that you still hold the permission to see this key.
        </Alert>
      ) : usage.isPending ? (
        <Text size="sm" c="dimmed">
          Loading usage…
        </Text>
      ) : (
        <>
          <Group gap="lg" aria-live="polite">
            <Figure label="Requests" value={usage.data.requests} />
            <Figure label="Denied" value={usage.data.denied} />
            <Figure label="Rate limited" value={usage.data.limited} />
            <Figure label="Errors" value={usage.data.errors} />
          </Group>
          {usage.data.perDay.length === 0 ? (
            <Text size="sm" c="dimmed">
              No requests in this period. Counts appear within a minute of a key being used.
            </Text>
          ) : (
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Day (UTC)</Table.Th>
                  <Table.Th ta="end">Requests</Table.Th>
                  <Table.Th ta="end">Denied</Table.Th>
                  <Table.Th ta="end">Rate limited</Table.Th>
                  <Table.Th ta="end">Errors</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {usage.data.perDay.map((d) => (
                  <Table.Tr key={d.day}>
                    <Table.Td style={NUMERIC}>{d.day}</Table.Td>
                    <Table.Td ta="end" style={NUMERIC}>
                      {d.requests}
                    </Table.Td>
                    <Table.Td ta="end" style={NUMERIC}>
                      {d.denied}
                    </Table.Td>
                    <Table.Td ta="end" style={NUMERIC}>
                      {d.limited}
                    </Table.Td>
                    <Table.Td ta="end" style={NUMERIC}>
                      {d.errors}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </>
      )}
    </Stack>
  );
}

function Figure({ label, value }: { label: string; value: number }) {
  return (
    <Stack gap={0}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="lg" fw={600} style={NUMERIC}>
        {value}
      </Text>
    </Stack>
  );
}

import { Group, Text } from '@mantine/core';

import { RedactionMarks } from '../../ui/RedactedValue.tsx';
import { redactionsAt } from '../../ui/redactions.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { SqlRowView } from './api.ts';
import { sourceOf } from './resultColumns.ts';
import { VerifyOnBroker } from './VerifyOnBroker.tsx';

/** The row's provenance in a word, with what it claims on hover. */
export function SourceBadge({ row }: Readonly<{ row: SqlRowView }>) {
  const { word, claim } = sourceOf(row);
  return (
    <span title={claim}>
      <StatusBadge>{word}</StatusBadge>
    </span>
  );
}

/** The body's preview, and that it was cut, withheld or redacted: a preview that looks complete misleads. */
export function BodyCell({ row: r }: Readonly<{ row: SqlRowView }>) {
  const withheld = r.withheld ?? [];
  return (
    <Group gap="xs" wrap="nowrap">
      <Text size="xs" truncate>
        {r.body ?? (
          <Text span c="dimmed">
            (empty)
          </Text>
        )}
      </Text>
      {r.bodyTruncated ? (
        <span title="Cut by the management channel">
          <StatusBadge tone="warning">truncated</StatusBadge>
        </span>
      ) : null}
      {withheld.length > 0 ? (
        <span title={withheld.map((w) => w.reason).join(' ')}>
          <StatusBadge>withheld</StatusBadge>
        </span>
      ) : null}
      <RedactionMarks redactions={redactionsAt(r.redactions ?? [], 'BODY')} />
    </Group>
  );
}

/**
 * Whether the broker still holds an indexed message. Only an indexed row raises the question: a live
 * row was read from the broker moments ago, so offering to re-ask would be theatre.
 */
export function VerifyCell({ clusterId, row }: Readonly<{ clusterId: string; row: SqlRowView }>) {
  return row.source === 'INDEX' ? <VerifyOnBroker clusterId={clusterId} row={row} /> : null;
}

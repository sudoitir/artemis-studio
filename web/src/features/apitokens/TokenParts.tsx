import { useState } from 'react';
import { Button, CopyButton, SegmentedControl, Stack, Text, TextInput, VisuallyHidden } from '@mantine/core';
import { IconCheck, IconCopy } from '@tabler/icons-react';

import { DialogActions } from '../../ui/DialogActions.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Stat } from '../../ui/Stat.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useTokenUsage, type UsagePeriod } from './api.ts';
import { usageColumns } from './columns.ts';
import classes from './TokenParts.module.css';

/**
 * A secret disclosed once, at minting or rotation, with a copy control that says when it copied. The dialog
 * around it cannot be dismissed by a stray click or key: only `onDone`, the explicit "I've copied the key".
 */
export function OneTimeSecret({ value, note, onDone }: Readonly<{ value: string; note?: string; onDone: () => void }>) {
  return (
    <Stack gap="sm">
      <div>
        <StatusBadge tone="warning">Shown once</StatusBadge>
        <Text size="sm">This value is shown once. Copy it now — it cannot be retrieved again.</Text>
      </div>
      {note ? <Text size="sm">{note}</Text> : null}
      <FieldRow>
        <TextInput classNames={{ input: classes.secret }} value={value} readOnly aria-label="API key" />
        <CopyButton value={value}>
          {({ copy, copied }) => (
            <>
              <Button
                variant="default"
                leftSection={copied ? <IconCheck size="1rem" aria-hidden /> : <IconCopy size="1rem" aria-hidden />}
                onClick={copy}
              >
                {copied ? 'Copied' : 'Copy key'}
              </Button>
              <VisuallyHidden role="status">{copied ? 'Key copied to the clipboard.' : ''}</VisuallyHidden>
            </>
          )}
        </CopyButton>
      </FieldRow>
      <Text size="xs" c="dimmed">
        This window stays open until you confirm below, so the key is not lost by a stray click or key press.
      </Text>
      <DialogActions>
        <Button onClick={onDone}>I&apos;ve copied the key</Button>
      </DialogActions>
    </Stack>
  );
}

const PERIODS = [
  { value: '1', label: 'Today' },
  { value: '7', label: '7 days' },
  { value: '30', label: '30 days' },
];

/** One token's requests per day over the chosen period, with denials, limits and errors. */
export function TokenUsagePanel({ scope, tokenId }: Readonly<{ scope: 'own' | 'admin'; tokenId: string }>) {
  const [days, setDays] = useState<UsagePeriod>(7);
  const usage = useTokenUsage(scope, tokenId, days);

  return (
    <Stack gap="md">
      <SegmentedControl
        size="xs"
        aria-label="Period"
        value={String(days)}
        onChange={(v) => setDays(Number(v) as UsagePeriod)}
        data={PERIODS}
      />
      <UsageBody usage={usage} />
    </Stack>
  );
}

function UsageBody({ usage }: Readonly<{ usage: ReturnType<typeof useTokenUsage> }>) {
  if (usage.isError) {
    return <ErrorState error={usage.error} onRetry={() => void usage.refetch()} />;
  }
  if (usage.isPending) {
    return <LoadingState label="Loading usage" blockSize="14rem" />;
  }
  return (
    <Stack gap="md">
      <div className={classes.figures} aria-live="polite">
        <Stat label="Requests" value={usage.data.requests} />
        <Stat label="Denied" value={usage.data.denied} />
        <Stat label="Rate limited" value={usage.data.limited} />
        <Stat label="Errors" value={usage.data.errors} />
      </div>
      <DataTable
        variant="static"
        label="Requests per day"
        columns={usageColumns()}
        data={usage.data.perDay}
        rowKey={(d) => d.day}
        empty={
          <EmptyState
            kind="empty"
            title="No requests in this period"
            description="Counts appear within a minute of a key being used."
          />
        }
      />
    </Stack>
  );
}

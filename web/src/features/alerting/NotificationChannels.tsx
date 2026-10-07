import { useState } from 'react';
import { Button, Stack, Text } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useServerNow } from '../../kernel/time/time.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable } from '../../ui/table/index.ts';
import {
  useDeleteNotificationChannel,
  useNotificationChannels,
  useTestNotificationChannel,
  type ChannelTestResultView,
  type NotificationChannelView,
} from './api.ts';
import { ChannelEditor, TestOutcome } from './ChannelEditor.tsx';
import { channelColumns } from './columns.ts';
import { DeliveryLog } from './DeliveryLog.tsx';

const TEST: ActionVerb = { verb: 'Send', past: 'Sent', progressive: 'Sending' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

const rowKey = (c: NotificationChannelView) => c.id;

const WRITE_REASON = 'Changing channels needs the alert:write permission.';

/**
 * Global notification channels — Slack, Microsoft Teams, PagerDuty, email and signed
 * webhooks (alerting spec, ADR-0036, ADR-0105). Each row states where it delivers, how
 * many rules route to it, and how its last delivery went, so a channel that has been
 * failing is visible without opening it. Secrets are write-only and never rendered.
 */
export function NotificationChannels() {
  const channels = useNotificationChannels();
  const test = useTestNotificationChannel();
  const { can, loading: grantsLoading } = useCan();
  // While grants load, offer the control; the server is the enforcement point.
  const canWrite = grantsLoading || can('alert:write');
  const now = useServerNow(30_000);

  const [editing, setEditing] = useState<NotificationChannelView | null>(null);
  const [creating, setCreating] = useState(false);
  const [logFor, setLogFor] = useState<NotificationChannelView | null>(null);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [deleting, setDeleting] = useState<NotificationChannelView | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [tested, setTested] = useState<{ channel: NotificationChannelView; result: ChannelTestResultView } | null>(
    null,
  );

  const runTest = (c: NotificationChannelView) =>
    test.mutate(c.id, {
      onSuccess: (result) => setTested({ channel: c, result }),
      onError: (error) =>
        notify.settle(error, {
          action: TEST,
          subject: `a test notification to ${c.name}`,
          cause: error.message,
          next: 'Nothing was sent. Try again.',
        }),
    });

  // Built each render: the cells carry what is gated and busy right now.
  const columns = channelColumns({
    now,
    controls: {
      canWrite,
      testingId: test.isPending ? test.variables : undefined,
      onTest: runTest,
      onLog: setLogFor,
      onEdit: setEditing,
      onDelete: (c) => {
        setDeleting(c);
        setDeleteOpen(true);
      },
    },
  });

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        Bind a channel to a rule from the rule form. A rule with no channel still fires and records history.
      </Text>
      {canWrite ? null : <Text size="sm">{WRITE_REASON}</Text>}

      {/* Mounted before any result, so a screen reader announces the test when it lands. */}
      <div role="status" aria-live="polite">
        {tested ? (
          <Stack gap={4}>
            <Text size="sm" fw={600}>
              Test of {tested.channel.name}
            </Text>
            <TestOutcome result={tested.result} kind={tested.channel.kind} />
          </Stack>
        ) : null}
      </div>

      <DataTable
        variant="static"
        label="Notification channels"
        storageKey="alerting.channels"
        columns={columns}
        data={channels.data ?? []}
        rowKey={rowKey}
        loading={channels.isPending}
        error={
          channels.isError ? <ErrorState error={channels.error} onRetry={() => void channels.refetch()} /> : undefined
        }
        toolbar={{
          start: (
            <Button onClick={() => setCreating(true)} disabled={!canWrite}>
              Add channel
            </Button>
          ),
        }}
        empty={
          <EmptyState
            kind="empty"
            title="No notification channels configured"
            description="Alert rules can still fire and record history, they just won’t deliver anywhere until a channel is bound. Add Slack, Microsoft Teams, PagerDuty, email or a signed webhook."
          />
        }
      />

      <ChannelEditor
        opened={creating || editing !== null}
        channel={editing}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
      />
      <DeliveryLog channel={logFor} onClose={() => setLogFor(null)} canWrite={canWrite} />
      <DeleteChannel channel={deleting} opened={deleteOpen} onClose={() => setDeleteOpen(false)} />
    </Stack>
  );
}

/** States what deleting silences before it can be armed, then asks for the name. */
function DeleteChannel({
  channel,
  opened,
  onClose,
}: Readonly<{ channel: NotificationChannelView | null; opened: boolean; onClose: () => void }>) {
  const remove = useDeleteNotificationChannel();
  const bound = channel?.boundRuleCount ?? 0;
  const routes = bound === 1 ? 'rule routes' : 'rules route';

  const confirm = (c: NotificationChannelView) =>
    remove.mutate(c.id, {
      onSuccess: () => {
        onClose();
        notify.succeeded({ action: DELETE, subject: `channel "${c.name}"` });
      },
      onError: (error) =>
        notify.settle(error, {
          action: DELETE,
          subject: `channel "${c.name}"`,
          cause: error.message,
          next: 'It is still configured. Try again.',
        }),
    });

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title="Delete channel"
      tone="danger"
      typedName={channel?.name}
      pending={remove.isPending}
      confirmLabel="Delete channel"
      consequence={
        channel ? (
          <>
            <Text component="p" size="sm" mb="xs">
              {bound === 0
                ? 'No rule routes to this channel, so no alert stops being delivered.'
                : `${bound} ${routes} to this channel and will stop delivering to it. Their other channels are unaffected; a rule left with none still fires and records history.`}
            </Text>
            <Text component="p" size="sm">
              Its delivery log is deleted with it. This cannot be undone.
            </Text>
          </>
        ) : null
      }
      onConfirm={() => channel && confirm(channel)}
    />
  );
}

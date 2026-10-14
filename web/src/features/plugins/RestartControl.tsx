import { useState } from 'react';
import { Button, Code, Stack, Text } from '@mantine/core';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useRestartRahdar, type RahdarRestartView } from './api.ts';
import { ConfirmAction } from './ConfirmAction.tsx';
import { Notice } from '../../ui/Notice.tsx';
import styles from './Plugins.module.css';

const REQUEST: ActionVerb = { verb: 'Restart', past: 'Requested', progressive: 'Requesting' };

/**
 * Restarting Rahdar for its plugins (ADR-0104): a button, with the same confirmation as any
 * other plugin change, when Rahdar can restart itself; the command otherwise. It says who is
 * affected — everyone — before it can be pressed.
 */
export function RestartControl({
  restart,
  reasons,
  canAct,
}: Readonly<{
  restart: RahdarRestartView;
  reasons: string[];
  canAct: boolean;
}>) {
  const [open, setOpen] = useState(false);
  const request = useRestartRahdar();
  useDisplayZone();

  if (restart.restarting) {
    return (
      <Notice title="Rahdar is restarting" tone="info">
        This page reconnects by itself; everyone is disconnected until Rahdar is back, usually under a minute.
      </Notice>
    );
  }
  if (!restart.needed) return null;

  const allowedAt = restart.allowedAt ? new Date(restart.allowedAt) : null;

  return (
    <Notice title="Rahdar needs a restart" tone="warning">
      <Stack gap="xs">
        <Text size="sm">{reasons.join(' ')}</Text>
        {restart.supervised ? (
          <div className={styles.controls}>
            <Button size="xs" onClick={() => setOpen(true)} disabled={!canAct}>
              Restart Rahdar…
            </Button>
            {!canAct ? (
              <Text size="xs" c="dimmed">
                Only someone who can install plugins can restart Rahdar.
              </Text>
            ) : null}
          </div>
        ) : (
          <>
            <Text size="sm">Rahdar cannot restart itself where it runs. Restart it with:</Text>
            <Code block>{restart.command}</Code>
          </>
        )}
      </Stack>
      <ConfirmAction
        opened={open}
        onClose={() => setOpen(false)}
        title="Restart Rahdar"
        confirmLabel="Restart Rahdar now"
        danger
        pending={request.isPending}
        error={request.error}
        returnTo={`${globalThis.location.pathname}?tab=plugins`}
        onConfirm={() =>
          request.mutate(undefined, {
            onSuccess: () => {
              notify.succeeded({ action: REQUEST, subject: 'a restart of Rahdar' });
              setOpen(false);
            },
          })
        }
      >
        <Stack gap="xs">
          <Text size="sm">
            Everyone using Rahdar is disconnected until it is back, usually under a minute. Nothing in progress on your
            brokers is affected. Every running plugin stops and starts again.
          </Text>
          {allowedAt && allowedAt.getTime() > Date.now() ? (
            <Text size="sm">
              Rahdar started moments ago; a restart is allowed from {absoluteLabel(restart.allowedAt)}.
            </Text>
          ) : null}
        </Stack>
      </ConfirmAction>
    </Notice>
  );
}

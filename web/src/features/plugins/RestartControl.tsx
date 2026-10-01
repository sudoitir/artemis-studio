import { useState } from 'react';
import { Button, Code, Stack, Text } from '@mantine/core';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useRestartStudio, type StudioRestartView } from './api.ts';
import { ConfirmAction } from './ConfirmAction.tsx';
import { Notice } from './Notice.tsx';
import styles from './Plugins.module.css';

const REQUEST: ActionVerb = { verb: 'Restart', past: 'Requested', progressive: 'Requesting' };

/**
 * Restarting Studio for its plugins (ADR-0104): a button, with the same confirmation as any
 * other plugin change, when Studio can restart itself; the command otherwise. It says who is
 * affected — everyone — before it can be pressed.
 */
export function RestartControl({
  restart,
  reasons,
  canAct,
}: Readonly<{
  restart: StudioRestartView;
  reasons: string[];
  canAct: boolean;
}>) {
  const [open, setOpen] = useState(false);
  const request = useRestartStudio();
  useDisplayZone();

  if (restart.restarting) {
    return (
      <Notice title="Studio is restarting">
        This page reconnects by itself; everyone is disconnected until Studio is back, usually under a minute.
      </Notice>
    );
  }
  if (!restart.needed) return null;

  const allowedAt = restart.allowedAt ? new Date(restart.allowedAt) : null;

  return (
    <Notice title="Studio needs a restart" tone="warning">
      <Stack gap="xs">
        <Text size="sm">{reasons.join(' ')}</Text>
        {restart.supervised ? (
          <div className={styles.controls}>
            <Button size="xs" onClick={() => setOpen(true)} disabled={!canAct}>
              Restart Studio…
            </Button>
            {!canAct ? (
              <Text size="xs" c="dimmed">
                Only someone who can install plugins can restart Studio.
              </Text>
            ) : null}
          </div>
        ) : (
          <>
            <Text size="sm">Studio cannot restart itself where it runs. Restart it with:</Text>
            <Code block>{restart.command}</Code>
          </>
        )}
      </Stack>
      <ConfirmAction
        opened={open}
        onClose={() => setOpen(false)}
        title="Restart Studio"
        confirmLabel="Restart Studio now"
        danger
        pending={request.isPending}
        error={request.error}
        returnTo={`${globalThis.location.pathname}?tab=plugins`}
        onConfirm={() =>
          request.mutate(undefined, {
            onSuccess: () => {
              notify.succeeded({ action: REQUEST, subject: 'a restart of Studio' });
              setOpen(false);
            },
          })
        }
      >
        <Stack gap="xs">
          <Text size="sm">
            Everyone using Studio is disconnected until it is back, usually under a minute. Nothing in progress on your
            brokers is affected. Every running plugin stops and starts again.
          </Text>
          {allowedAt && allowedAt.getTime() > Date.now() ? (
            <Text size="sm">
              Studio started moments ago; a restart is allowed from {absoluteLabel(restart.allowedAt)}.
            </Text>
          ) : null}
        </Stack>
      </ConfirmAction>
    </Notice>
  );
}

import { useState } from 'react';
import { Alert, Button, Code, Group, Modal, Stack, Text } from '@mantine/core';

import { needsReauthentication, useRestartStudio, violationsOf, type StudioRestartView } from './api.ts';
import { useFreshSignIn } from './freshSignIn.ts';
import { StepUp } from './StepUp.tsx';

/**
 * Restarting Studio for its plugins (ADR-0104): a button, with the same confirmation as any
 * other plugin change, when Studio can restart itself; the command otherwise. It says who is
 * affected — everyone — before it can be pressed.
 */
export function RestartControl({ restart, reasons, canAct }: { restart: StudioRestartView; reasons: string[]; canAct: boolean }) {
  const [open, setOpen] = useState(false);
  const request = useRestartStudio();
  const fresh = useFreshSignIn();

  if (restart.restarting) {
    return (
      <Alert variant="light" title="Studio is restarting" aria-live="polite">
        This page reconnects by itself; everyone is disconnected until Studio is back, usually under a minute.
      </Alert>
    );
  }
  if (!restart.needed) return null;

  const allowedAt = restart.allowedAt ? new Date(restart.allowedAt) : null;
  const refusal = request.error && !needsReauthentication(request.error)
    ? violationsOf(request.error).map((v) => `${v.message} ${v.fix}`).join(' ') || request.error.message
    : null;

  return (
    <Alert variant="light" title="Studio needs a restart" color="yellow">
      <Stack gap="xs">
        <Text size="sm">{reasons.join(' ')}</Text>
        {restart.supervised ? (
          <Group gap="xs">
            <Button size="xs" onClick={() => setOpen(true)} disabled={!canAct}>
              Restart Studio…
            </Button>
            {!canAct ? (
              <Text size="xs" c="dimmed">
                Only someone who can install plugins can restart Studio.
              </Text>
            ) : null}
          </Group>
        ) : (
          <>
            <Text size="sm">Studio cannot restart itself where it runs. Restart it with:</Text>
            <Code block>{restart.command}</Code>
          </>
        )}
      </Stack>
      <Modal opened={open} onClose={() => setOpen(false)} title="Restart Studio">
        <Stack gap="md">
          <Text size="sm">
            Everyone using Studio is disconnected until it is back, usually under a minute. Nothing in progress on
            your brokers is affected. Every running plugin stops and starts again.
          </Text>
          {allowedAt && allowedAt.getTime() > Date.now() ? (
            <Text size="sm">Studio started moments ago; a restart is allowed from {allowedAt.toLocaleTimeString()}.</Text>
          ) : null}
          <StepUp returnTo={`${window.location.pathname}?tab=plugins`} />
          {refusal ? (
            <Alert color="red" variant="light" role="alert">
              {refusal}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              color="red"
              loading={request.isPending}
              disabled={!fresh}
              onClick={() => request.mutate(undefined, { onSuccess: () => setOpen(false) })}
            >
              Restart Studio now
            </Button>
          </Group>
          {!fresh ? (
            <Text size="xs" c="dimmed" ta="end">
              Confirm it is you above first.
            </Text>
          ) : null}
        </Stack>
      </Modal>
    </Alert>
  );
}

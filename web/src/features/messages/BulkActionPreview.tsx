import { useRef, useState } from 'react';
import { Button, Modal, Stack, Text, TextInput } from '@mantine/core';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { useMessageAction, type DryRunView, type MessageActionKind } from './api.ts';
import { announceFailure, announceResult, destroys, messageCount, VERBS } from './outcomes.ts';

/**
 * By-selector action with a mandatory preview (ADR-0022). "Preview" runs `dryRun=true` and takes the
 * broker's point-in-time estimate; the confirmation then states it and the cap. Deleting or expiring,
 * or going over the cap, is armed by typing the queue's name, and an over-cap run resends with
 * `override=true`. The selector and the target are checked on activation, with the reason beside the
 * field.
 */
export function BulkActionPreview({
  clusterId,
  queueName,
  action,
  node,
  opened,
  onClose,
  onDone,
}: Readonly<{
  clusterId: string;
  queueName: string;
  action: MessageActionKind;
  node?: string;
  opened: boolean;
  onClose: () => void;
  onDone: () => void;
}>) {
  const run = useMessageAction(clusterId, queueName);
  const [filter, setFilter] = useState('');
  const [target, setTarget] = useState('');
  const [errors, setErrors] = useState<{ filter?: string; target?: string }>({});
  const [preview, setPreview] = useState<DryRunView | null>(null);
  const filterRef = useRef<HTMLInputElement>(null);
  const targetRef = useRef<HTMLInputElement>(null);
  const verb = VERBS[action].verb;

  const body = () => ({
    filter,
    targetQueue: action === 'move' ? target : undefined,
  });

  const check = (): { filter?: string; target?: string } => ({
    filter: action !== 'retry' && !filter.trim() ? 'Enter the selector the messages must match.' : undefined,
    target: action === 'move' && !target.trim() ? 'Name the queue to move the messages to.' : undefined,
  });

  const doPreview = () => {
    const found = check();
    setErrors(found);
    if (found.filter) {
      filterRef.current?.focus();
      return;
    }
    if (found.target) {
      targetRef.current?.focus();
      return;
    }
    run.mutate(
      { action, body: body(), node, dryRun: true },
      {
        onSuccess: (r) => setPreview('cap' in r ? r : null),
        onError: (e) => announceFailure(action, `the preview of the selector on queue "${queueName}"`, e),
      },
    );
  };

  const reset = () => {
    setFilter('');
    setTarget('');
    setErrors({});
    setPreview(null);
  };

  const close = () => {
    reset();
    onClose();
  };

  const execute = () =>
    run.mutate(
      { action, body: body(), node, override: preview?.overCap ?? false },
      {
        onSuccess: (r) => {
          announceResult(action, queueName, r, preview?.affectedCount ?? 0);
          reset();
          onDone();
        },
        onError: (e) =>
          announceFailure(action, `${messageCount(preview?.affectedCount ?? 0)} in queue "${queueName}"`, e),
      },
    );

  const overCap = preview?.overCap ?? false;
  const armed = destroys(action) || overCap;
  return (
    <>
      <Modal opened={opened && preview === null} onClose={close} title={`${verb} by selector`} size="lg">
        <Stack gap="sm">
          {action === 'retry' ? (
            <Text size="sm" c="dimmed">
              Artemis has no by-selector retry — this replays <strong>every</strong> message on the queue. Preview to
              see how many.
            </Text>
          ) : (
            <TextInput
              ref={filterRef}
              label="Selector"
              placeholder="region = 'eu' AND priority > 4"
              value={filter}
              error={errors.filter}
              onChange={(e) => setFilter(e.currentTarget.value)}
              onBlur={() => setErrors((prev) => ({ ...prev, filter: check().filter }))}
              size="xs"
              data-autofocus
            />
          )}
          {action === 'move' ? (
            <TextInput
              ref={targetRef}
              label="Target queue"
              value={target}
              error={errors.target}
              onChange={(e) => setTarget(e.currentTarget.value)}
              onBlur={() => setErrors((prev) => ({ ...prev, target: check().target }))}
              size="xs"
            />
          ) : null}
          <div>
            <Button size="xs" variant="default" loading={run.isPending} onClick={doPreview}>
              Preview
            </Button>
          </div>
        </Stack>
      </Modal>

      <ConfirmDialog
        opened={opened && preview !== null}
        onClose={close}
        title={`${verb} by selector`}
        consequence={
          preview ? (
            <Stack gap="xs">
              <Text size="sm">
                ≈ {messageCount(preview.affectedCount)} (estimate). Point-in-time count from the broker. Safety cap:{' '}
                {preview.cap.toLocaleString()}.
              </Text>
              {overCap ? (
                <Text size="sm">
                  That is over the cap. Confirming overrides it for this operation, and the override is recorded in the
                  audit log.
                </Text>
              ) : null}
              {destroys(action) ? <Text size="sm">This cannot be undone.</Text> : null}
            </Stack>
          ) : null
        }
        confirmLabel={`${verb} ${messageCount(preview?.affectedCount ?? 0)}${overCap ? ' anyway' : ''}`}
        tone={armed ? 'danger' : 'default'}
        typedName={armed ? queueName : undefined}
        pending={run.isPending}
        onConfirm={execute}
      />
    </>
  );
}

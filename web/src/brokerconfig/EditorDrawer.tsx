import type { ReactNode } from 'react';
import { Alert, Button, Drawer, Group, Stack, Text } from '@mantine/core';

import type { ApiError } from '../api/client.ts';

/**
 * The frame every declaration editor shares: a side drawer (keyboard-complete,
 * focus returns to the row that opened it), the save error with its next
 * action, and a footer whose submit is never silently disabled.
 *
 * <p>A stale-revision refusal is the one error with a specific remedy, and it
 * is spelled out: someone else saved while this editor was open, so the edit
 * has to be made again on top of theirs.
 */
export function EditorDrawer({
  opened,
  onClose,
  title,
  error,
  submitting,
  submitLabel,
  onSubmit,
  hint,
  secondary,
  children,
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  error: ApiError | null;
  submitting: boolean;
  submitLabel: string;
  onSubmit: () => void;
  /** What stops the submit, once a submit was attempted; empty otherwise. */
  hint?: string;
  /** A second, non-primary action — "Remove from declaration". */
  secondary?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Drawer opened={opened} onClose={onClose} title={title} position="right" size="lg" padding="md">
      <Stack gap="md">
        {children}

        {error ? (
          <Alert color="red" variant="light" title={error.title} role="alert">
            {error.type.endsWith('stale-revision')
              ? `${error.message} Reload the declaration and make this edit again on top of the newer revision.`
              : error.message}
          </Alert>
        ) : null}

        <Group justify="space-between" align="center">
          <Text size="xs" c="dimmed">
            {hint ?? ''}
          </Text>
          <Group gap="xs">
            {secondary}
            <Button variant="default" size="xs" onClick={onClose}>
              Cancel
            </Button>
            <Button size="xs" loading={submitting} onClick={onSubmit}>
              {submitLabel}
            </Button>
          </Group>
        </Group>
      </Stack>
    </Drawer>
  );
}

export const MATCH_HINT =
  'Artemis wildcard: "." separates words, "*" matches one word, "#" matches any number of words. "#" alone covers every address.';

import type { ReactNode, SyntheticEvent } from 'react';
import { Button, Drawer, Group, Stack, Text } from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { MiddleTruncate } from '../../ui/table/MiddleTruncate.tsx';
import classes from './Configuration.module.css';

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
  closeOnEscape = true,
  children,
}: Readonly<{
  opened: boolean;
  onClose: () => void;
  title: string;
  error: ApiError | null;
  submitting: boolean;
  submitLabel: string;
  /** The form's submit handler: `form.onSubmit(save, focusFirstInvalid(form.getInputNode))`. */
  onSubmit: (event?: SyntheticEvent<HTMLFormElement>) => void;
  /** What stops the submit, once a submit was attempted; empty otherwise. */
  hint?: string;
  /** A second, non-primary action — "Remove from declaration". */
  secondary?: ReactNode;
  /**
   * False while a section inside the drawer owns Escape — an inline form that Escape collapses.
   * Mantine listens for Escape on the window, so the section cannot stop it reaching the drawer.
   */
  closeOnEscape?: boolean;
  children: ReactNode;
}>) {
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      title={<MiddleTruncate text={title} tooltip />}
      classNames={{ title: classes.drawerTitle }}
      position="right"
      size="lg"
      padding="md"
      closeOnEscape={closeOnEscape}
    >
      <form noValidate onSubmit={onSubmit}>
        <Stack gap="md">
          {children}

          {error ? (
            <Stack gap="sm">
              <ErrorState error={error} />
              {error.type.endsWith('stale-revision') ? (
                <Text size="sm">
                  Someone saved while this editor was open. Reload the declaration and make this edit again on top of
                  the newer revision.
                </Text>
              ) : null}
            </Stack>
          ) : null}

          <Group justify="space-between" align="center">
            <Text size="sm" c="dimmed">
              {hint ?? ''}
            </Text>
            <Group gap="xs">
              {secondary}
              <Button variant="default" size="xs" onClick={onClose}>
                Cancel
              </Button>
              <Button type="submit" size="xs" loading={submitting}>
                {submitLabel}
              </Button>
            </Group>
          </Group>
        </Stack>
      </form>
    </Drawer>
  );
}

export const MATCH_HINT =
  'Artemis wildcard: "." separates words, "*" matches one word, "#" matches any number of words. "#" alone covers every address.';

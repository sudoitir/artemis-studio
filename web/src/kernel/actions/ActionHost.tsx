import {
  useCallback,
  useMemo,
  useRef,
  useState,
  type ComponentType,
  type ReactNode,
} from 'react';
import { Button, Group, Modal, Stack } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { CapabilityReason } from '../../ui/CapabilityReason.tsx';
import { HostContext } from './hostContext.ts';
import type { ActionHost, HostedDialogProps } from './types.ts';

/** How long a closing dialog is kept mounted for its exit transition before it is removed. */
const EXIT_MS = 250;

interface Entry {
  id: number;
  Dialog: ComponentType<HostedDialogProps>;
  props: object;
  opened: boolean;
  restoreFocus?: () => void;
  /** Where the dialog was opened: focus is restored only if the operator is still there. */
  href: string;
}

/** A blocked action's explanation, as a hosted dialog. */
function Explanation({
  opened,
  onClose,
  what,
  reason,
  snippet,
}: HostedDialogProps & { what: string; reason: string; snippet?: string | null }) {
  return (
    <Modal opened={opened} onClose={onClose} title={`Why ${what} is unavailable`} size="md">
      <Stack gap="md">
        <CapabilityReason reason={reason} snippet={snippet} size="sm" />
        <Group justify="flex-end">
          <Button size="xs" variant="default" onClick={onClose}>
            Close
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/**
 * Hosts the dialogs row actions open (ADR-0107), outside any grid: a dialog mounted in a
 * virtualized row is unmounted when the row scrolls away or when the refresh its own success
 * triggers removes the row, and its outcome goes with it.
 *
 * <p>A dialog is mounted closed and opened on the next frame. Dialogs take their dry-run preview
 * in `onEnterTransitionEnd`, which a modal mounted already open never fires. On close it is kept
 * for its exit transition, then removed, and focus goes back to what opened it — unless the dialog
 * navigated away, where moving focus would be a jump the operator did not ask for.
 */
export function ActionHostProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const nextId = useRef(1);
  const entriesRef = useRef(entries);
  entriesRef.current = entries;

  const close = useCallback((id: number) => {
    setEntries((all) => all.map((e) => (e.id === id ? { ...e, opened: false } : e)));
    window.setTimeout(() => {
      const entry = entriesRef.current.find((e) => e.id === id);
      setEntries((all) => all.filter((e) => e.id !== id));
      if (entry?.restoreFocus && window.location.href === entry.href) entry.restoreFocus();
    }, EXIT_MS);
  }, []);

  const host = useMemo<ActionHost>(() => {
    const open: ActionHost['open'] = (Dialog, props, options) => {
      const id = nextId.current++;
      setEntries((all) => [
        ...all,
        {
          id,
          Dialog: Dialog as ComponentType<HostedDialogProps>,
          props,
          opened: false,
          restoreFocus: options?.restoreFocus,
          href: window.location.href,
        },
      ]);
      requestAnimationFrame(() =>
        setEntries((all) => all.map((e) => (e.id === id ? { ...e, opened: true } : e))),
      );
    };
    return {
      open,
      explain: (verdict, what, options) =>
        open(Explanation, { what, reason: verdict.reason, snippet: verdict.snippet }, options),
      copy: (text, what) => {
        const done = (message: string, failed = false) =>
          notifications.show({
            message,
            autoClose: failed ? 6000 : 2500,
            color: failed ? 'red' : undefined,
            // Polite, not an alert: a copy is a confirmation, not an interruption.
            role: 'status',
          });
        if (!navigator.clipboard) {
          done(`The browser does not allow copying here. Select the ${what} and copy it by hand.`, true);
          return;
        }
        navigator.clipboard.writeText(text).then(
          () => done(`Copied the ${what}.`),
          () => done(`Copying the ${what} was refused by the browser. Select it and copy it by hand.`, true),
        );
      },
    };
  }, []);

  return (
    <HostContext.Provider value={host}>
      {children}
      {entries.map(({ id, Dialog, props, opened }) => (
        <Dialog key={id} {...props} opened={opened} onClose={() => close(id)} />
      ))}
    </HostContext.Provider>
  );
}

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ComponentType,
  type ReactNode,
  type TransitionEvent,
} from 'react';
import { Button, Group, Modal, Stack, useMantineTheme } from '@mantine/core';
import { useReducedMotion } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';

import { CapabilityReason } from '../../ui/CapabilityReason.tsx';
import { HostContext } from './hostContext.ts';
import type { ActionHost, HostedDialogProps } from './types.ts';

/** The surface whose exit transition ends a hosted dialog: a modal's or a drawer's content. */
const DIALOG_SURFACE = '.mantine-Modal-content, .mantine-Drawer-content';

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
 * until its exit transition ends, then removed, and focus goes back to what opened it — unless the
 * dialog navigated away, where moving focus would be a jump the operator did not ask for. The end
 * is heard as the surface's `transitionend`, which React bubbles out of the dialog's portal; a
 * timer as long as the slowest exit in the theme removes it if that event never comes.
 */
export function ActionHostProvider({ children }: Readonly<{ children: ReactNode }>) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const nextId = useRef(1);
  const entriesRef = useRef(entries);
  entriesRef.current = entries;
  // Exit timers still pending when the provider unmounts are cleared, so none updates state after it.
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>());
  useEffect(() => {
    const pending = timers.current;
    return () => pending.forEach((t) => globalThis.clearTimeout(t));
  }, []);

  const { other } = useMantineTheme();
  const reduceMotion = useReducedMotion();
  // A drawer's exit, the slowest in the theme, is base; slow leaves room for the frames Mantine waits
  // before it starts. Reduced motion has no exit to wait for.
  const exitMs = reduceMotion ? 0 : (other.motion.slow as number);

  // Removes a closed dialog once, by whichever comes first: its exit transition's end or the timer.
  const exitTimers = useRef(new Map<number, ReturnType<typeof setTimeout>>());
  const remove = useCallback((id: number) => {
    const timer = exitTimers.current.get(id);
    if (timer === undefined) return;
    globalThis.clearTimeout(timer);
    timers.current.delete(timer);
    exitTimers.current.delete(id);
    const entry = entriesRef.current.find((e) => e.id === id);
    setEntries((all) => all.filter((e) => e.id !== id));
    if (entry?.restoreFocus && globalThis.location.href === entry.href) entry.restoreFocus();
  }, []);

  const close = useCallback(
    (id: number) => {
      if (exitTimers.current.has(id)) return;
      setEntries((all) => all.map((e) => (e.id === id ? { ...e, opened: false } : e)));
      const timer = globalThis.setTimeout(() => remove(id), exitMs);
      timers.current.add(timer);
      exitTimers.current.set(id, timer);
    },
    [exitMs, remove],
  );

  // Only the end of the exit counts: an enter cut short by a quick close ends at full opacity.
  const onExited = (id: number, e: TransitionEvent) => {
    const surface = e.target;
    if (surface instanceof HTMLElement && surface.matches(DIALOG_SURFACE) && surface.style.opacity === '0') remove(id);
  };

  const markOpened = useCallback(
    (id: number) => setEntries((all) => all.map((e) => (e.id === id ? { ...e, opened: true } : e))),
    [],
  );

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
          href: globalThis.location.href,
        },
      ]);
      requestAnimationFrame(() => markOpened(id));
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
  }, [markOpened]);

  return (
    <HostContext.Provider value={host}>
      {children}
      {entries.map(({ id, Dialog, props, opened }) => (
        // `display: contents` draws nothing; the element is only there to hear the dialog's transitions.
        <div key={id} style={{ display: 'contents' }} onTransitionEnd={opened ? undefined : (e) => onExited(id, e)}>
          <Dialog {...props} opened={opened} onClose={() => close(id)} />
        </div>
      ))}
    </HostContext.Provider>
  );
}

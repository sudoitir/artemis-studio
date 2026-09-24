import { useSyncExternalStore } from 'react';

/*
 * Whether single-key shortcuts are on (ADR-0109). A personal, browser-local preference like the
 * display time zone: WCAG 2.1.4 asks that character-key shortcuts can be turned off, because speech
 * input and switch users trigger them by accident. ⌘K and ⌘B carry a modifier and stay on.
 */

const KEY = 'as:shortcuts';
const listeners = new Set<() => void>();

function read(): boolean {
  try {
    return window.localStorage.getItem(KEY) !== 'off';
  } catch {
    return true;
  }
}

let enabled = typeof window === 'undefined' ? true : read();

export function singleKeyShortcutsEnabled(): boolean {
  return enabled;
}

export function setSingleKeyShortcuts(next: boolean) {
  enabled = next;
  try {
    window.localStorage.setItem(KEY, next ? 'on' : 'off');
  } catch {
    // Storage refused: the choice holds for this visit.
  }
  listeners.forEach((listener) => listener());
}

export function useSingleKeyShortcuts(): [boolean, (next: boolean) => void] {
  const value = useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => enabled,
  );
  return [value, setSingleKeyShortcuts];
}

// ── The shortcuts help dialog's open state, so the header, the palette and `?` share one dialog.
let helpOpen = false;
const helpListeners = new Set<() => void>();

export function setShortcutsHelpOpen(next: boolean) {
  helpOpen = next;
  helpListeners.forEach((listener) => listener());
}

export function useShortcutsHelpOpen(): boolean {
  return useSyncExternalStore(
    (listener) => {
      helpListeners.add(listener);
      return () => helpListeners.delete(listener);
    },
    () => helpOpen,
  );
}

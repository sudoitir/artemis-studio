import { useEffect, type RefObject } from 'react';

/** The filter field of the view on screen, which `/` focuses (ADR-0109). */
let current: RefObject<HTMLInputElement | null> | null = null;

/** Registers a view's filter field for `/` while the view is mounted. */
export function useFilterShortcut(ref: RefObject<HTMLInputElement | null>) {
  useEffect(() => {
    current = ref;
    return () => {
      if (current === ref) current = null;
    };
  }, [ref]);
}

/** Focuses the registered filter field; false when the view has none, so the key is left alone. */
export function focusFilter(): boolean {
  const input = current?.current;
  if (!input || !input.isConnected) return false;
  input.focus();
  input.select();
  return true;
}

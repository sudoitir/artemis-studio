import { useEffect, useSyncExternalStore } from 'react';

/**
 * The parts of "where the operator is" that the shell cannot derive from the address itself
 * (ADR-0109): the cluster's name, which only the clusters feature knows, and the open resource,
 * which only its view knows. The shell combines them with the view it matches from the address into
 * the document title and the breadcrumb.
 */
export interface TitleParts {
  cluster?: string;
  resource?: string;
}

let parts: TitleParts = {};
const listeners = new Set<() => void>();

function set(part: keyof TitleParts, value: string | undefined) {
  if (parts[part] === value) return;
  parts = { ...parts, [part]: value };
  listeners.forEach((listener) => listener());
}

export function useTitleParts(): TitleParts {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => parts,
  );
}

/** Declares one part of the title while the calling component is mounted. */
export function useTitlePart(part: keyof TitleParts, value: string | null | undefined) {
  useEffect(() => {
    set(part, value ?? undefined);
    return () => set(part, undefined);
  }, [part, value]);
}

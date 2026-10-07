import type { MouseEvent } from 'react';

/**
 * How a link rendered outside the router (a toast, which Mantine mounts above `RouterProvider`) moves
 * inside the app without reloading it. The composition root registers the router's navigation; until it
 * has, and for a click that asks for a new tab or window, the link behaves as the plain anchor it is.
 */
let navigate: ((to: string) => void) | null = null;

/** Registers how to move to an in-app path. Returns the unregistration. */
export function setInAppNavigate(next: (to: string) => void): () => void {
  navigate = next;
  return () => {
    if (navigate === next) navigate = null;
  };
}

/** A click handler for an anchor to `to`: a plain click navigates in the app, a modified one is left to the browser. */
export function followInApp(event: MouseEvent<HTMLAnchorElement>, to: string): void {
  if (!navigate || event.defaultPrevented || event.button !== 0) return;
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  navigate(to);
}

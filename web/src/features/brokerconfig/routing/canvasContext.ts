import { createContext } from 'react';

/** What every element on the routing canvas reads, set once by the canvas. */
export interface RoutingCanvasState {
  /** The element that holds the canvas's single tab stop; arrow keys move it. */
  focusedId: string | null;
  /** Whether the operator may compose on the canvas; handles show only then. */
  canWrite: boolean;
  /** Record where an element's focusable node lives, so the canvas can move focus to it. */
  register: (id: string, el: HTMLElement | null) => void;
  /** Take the tab stop, because the element was focused directly rather than by an arrow key. */
  focus: (id: string) => void;
  /** Open the inspector on an element. */
  select: (id: string) => void;
}

export const RoutingCanvasContext = createContext<RoutingCanvasState>({
  focusedId: null,
  canWrite: false,
  register: () => {},
  focus: () => {},
  select: () => {},
});

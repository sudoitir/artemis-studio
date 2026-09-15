import { createContext } from 'react';

/** What every node and edge on the flow canvas reads, set once by the canvas. */
export interface FlowCanvasState {
  /** Open the inspector for a node. */
  select: (id: string) => void;
  /** Emphasise the path through a node while it is hovered or focused; null clears it. */
  emphasize: (id: string | null) => void;
  /** Rate labels and moving dots are drawn only at a zoom where text is legible, together. */
  showText: boolean;
  /** `off` under reduced motion: no dots at all. `paused`: dots stay where they are. */
  motion: 'running' | 'paused' | 'off';
}

export const FlowCanvasContext = createContext<FlowCanvasState>({
  select: () => {},
  emphasize: () => {},
  showText: true,
  motion: 'off',
});

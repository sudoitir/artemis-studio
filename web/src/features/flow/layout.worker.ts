/// <reference lib="webworker" />
import ELK from 'elkjs/lib/elk.bundled.js';
import type { ElkNode } from 'elkjs/lib/elk-api';

import { positionsFrom } from './flowLayout.ts';

/**
 * Runs ELK off the main thread (ADR-0080): a 200-path layout takes about a second, which on the main
 * thread would freeze the console while it computes.
 */
const elk = new ELK();

self.onmessage = async (event: MessageEvent<{ id: number; graph: ElkNode }>) => {
  const { id, graph } = event.data;
  try {
    const laidOut = await elk.layout(graph);
    self.postMessage({ id, positions: positionsFrom(laidOut) });
  } catch (error) {
    self.postMessage({ id, error: error instanceof Error ? error.message : String(error) });
  }
};

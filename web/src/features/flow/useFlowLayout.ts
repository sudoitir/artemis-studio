import { useEffect, useMemo, useRef, useState } from 'react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import type { FlowGraphView } from './api.ts';
import { layoutSignature, positionsFrom, toElkGraph, type Positions } from './flowLayout.ts';

type Pending = { resolve: (p: Positions) => void; reject: (e: Error) => void };

let worker: Worker | null = null;
let nextId = 0;
const pending = new Map<number, Pending>();

function layoutWorker(): Worker {
  if (worker) return worker;
  worker = new Worker(new URL('./layout.worker.ts', import.meta.url), { type: 'module' });
  worker.onmessage = (event: MessageEvent<{ id: number; positions?: Positions; error?: string }>) => {
    const waiter = pending.get(event.data.id);
    pending.delete(event.data.id);
    if (!waiter) return;
    if (event.data.positions) waiter.resolve(event.data.positions);
    else waiter.reject(new Error(event.data.error ?? 'layout failed'));
  };
  return worker;
}

/** Lay a graph out in the worker, or on this thread where there is no worker (tests, old runtimes). */
export function runLayout(graph: ElkNode): Promise<Positions> {
  if (typeof Worker === 'undefined') {
    return import('elkjs/lib/elk.bundled.js').then(async ({ default: ELK }) =>
      positionsFrom(await new ELK().layout(graph)),
    );
  }
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    layoutWorker().postMessage({ id, graph });
  });
}

/**
 * Positions for a flow graph, recomputed only when its layout signature changes. While a new layout
 * runs, the previous positions stay, so a node that already existed does not jump.
 */
export function useFlowLayout(graph: FlowGraphView | undefined): {
  positions: Positions;
  pending: boolean;
  error: string | null;
} {
  const signature = useMemo(() => (graph ? layoutSignature(graph) : ''), [graph]);
  const latest = useRef(graph);
  latest.current = graph;
  const [state, setState] = useState<{ signature: string; positions: Positions }>({ signature: '', positions: {} });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const current = latest.current;
    if (!current || signature === '') return;
    let cancelled = false;
    runLayout(toElkGraph(current))
      .then((positions) => {
        if (!cancelled) {
          setState({ signature, positions });
          setError(null);
        }
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [signature]);

  return { positions: state.positions, pending: state.signature !== signature, error };
}

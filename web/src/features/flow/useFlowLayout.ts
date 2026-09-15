import { useEffect, useMemo, useRef, useState } from 'react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import type { FlowGraphView } from './api.ts';
import { layoutSignature, positionsFrom, toElkGraph, type Positions } from './flowLayout.ts';

import elkWorkerUrl from 'elkjs/lib/elk-worker.min.js?url';

/** What an ELK instance offers, whichever way the bundler hands the CommonJS module over. */
type Elk = { layout(graph: ElkNode): Promise<ElkNode> };
type ElkConstructor = new (options?: { workerUrl?: string }) => Elk;

/**
 * elkjs is CommonJS. A bundler may hand over the constructor as the module's default or as the
 * module itself; Node hands it over as the default. Take whichever is the constructor.
 */
function constructorOf(module: unknown): ElkConstructor {
  const candidate = (module as { default?: unknown }).default ?? module;
  return candidate as ElkConstructor;
}

let elk: Promise<Elk> | null = null;

/**
 * ELK's own worker runs the layout (ADR-0080): elk-api on this thread posts to elk-worker.min.js,
 * so a large graph never blocks the page. Where there is no Worker (Node, tests), the bundled build
 * runs on this thread instead. The instance is created once; a failed layout rejects, and the canvas
 * says the graph could not be laid out.
 */
function elkInstance(): Promise<Elk> {
  elk ??=
    typeof Worker === 'undefined'
      ? import('elkjs/lib/elk.bundled.js').then((m) => new (constructorOf(m))())
      : import('elkjs/lib/elk-api.js').then((m) => new (constructorOf(m))({ workerUrl: elkWorkerUrl }));
  return elk;
}

/** Lay a graph out and return where each node goes. */
export async function runLayout(graph: ElkNode): Promise<Positions> {
  return positionsFrom(await (await elkInstance()).layout(graph));
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

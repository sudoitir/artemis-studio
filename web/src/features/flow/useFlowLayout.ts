import { useEffect, useMemo, useRef, useState } from 'react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import { runLayout as runElkLayout } from '../../ui/graph/elk.ts';
import type { FlowGraphView } from './api.ts';
import { layoutSignature, positionsFrom, toElkGraph, type Positions } from './flowLayout.ts';

/** Lay a graph out and return where each node goes. */
export async function runLayout(graph: ElkNode): Promise<Positions> {
  return positionsFrom(await runElkLayout(graph));
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

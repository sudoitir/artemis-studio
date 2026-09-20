import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Background, Controls, ReactFlow, ReactFlowProvider, useReactFlow, type Connection } from '@xyflow/react';
import { Alert, Button, Loader, Text } from '@mantine/core';
import type { ElkNode } from 'elkjs/lib/elk-api';

import { runLayout as runElkLayout } from '../../../ui/graph/elk.ts';
import { RoutingCanvasContext, type RoutingCanvasState } from './canvasContext.ts';
import { KIND_WORDS, type RoutingGraph } from './routingGraph.ts';
import { layoutSignature, positionsFrom, toElkGraph, toReactFlow, type Positions } from './routingLayout.ts';
import { RoutingEdge } from './RoutingEdge.tsx';
import { AddressNode, BridgeNode, DivertNode, QueueNode, TargetNode } from './RoutingNodes.tsx';
import classes from './RoutingCanvas.module.css';

const nodeTypes = { address: AddressNode, queue: QueueNode, divert: DivertNode, bridge: BridgeNode, target: TargetNode };
const edgeTypes = { routing: RoutingEdge };

/** Above this many drawn elements React Flow renders only what is on screen (ADR-0056). */
const DENSE = 60;

/** What a drag between two elements proposes. Nothing is written until the document is saved. */
export type Compose =
  | { kind: 'divert'; address: string; forwardingAddress: string }
  | { kind: 'bridge'; queueName: string; forwardingAddress: string };

/** Positions for a routing graph, recomputed only when its structure changes. */
function useRoutingLayout(graph: RoutingGraph): { positions: Positions; pending: boolean; error: string | null } {
  const signature = useMemo(() => layoutSignature(graph), [graph]);
  const latest = useRef(graph);
  latest.current = graph;
  const [state, setState] = useState<{ signature: string; positions: Positions }>({ signature: '', positions: {} });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const current = latest.current;
    if (current.nodes.length === 0) {
      setState({ signature, positions: {} });
      return;
    }
    let cancelled = false;
    runElkLayout(toElkGraph(current) as ElkNode)
      .then((laidOut) => {
        if (cancelled) return;
        setState({ signature, positions: positionsFrom(laidOut) });
        setError(null);
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

/**
 * Fit once per layout, so an evaluation that changes only words keeps the operator's
 * view — and again whenever the canvas itself changes size, because the first fit
 * otherwise measures a box that the height measurement is about to change and leaves
 * the graph running off the bottom.
 */
function FitOnLayout({ signature }: { signature: string | null }) {
  const flow = useReactFlow();
  useEffect(() => {
    if (!signature) return;
    const fit = () => void flow.fitView({ padding: 0.14, maxZoom: 1 });
    const frame = requestAnimationFrame(fit);
    const box = document.querySelector(`.${classes.wrapper}`);
    const observer = box ? new ResizeObserver(fit) : null;
    if (box && observer) observer.observe(box);
    return () => {
      cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [flow, signature]);
  return null;
}

/**
 * The routing canvas (ADR-0090): the declaration and what the nodes report, drawn
 * as one graph, laid out by the shared ELK runner in a worker.
 *
 * <p>It authors the declaration and never writes to a broker. A drag between two
 * elements proposes a divert or a bridge and opens its editor; the editor saves a
 * revision; applying is the review drawer, unchanged (ADR-0090 D1, D2).
 *
 * <p>Nothing here animates on its own, so `operator-ui`'s pause requirement is
 * met by construction — the neighbouring flow canvas does animate, and the two
 * will be compared.
 */
export function RoutingCanvas({
  graph,
  selectedId,
  onSelect,
  onCompose,
  canWrite,
}: {
  graph: RoutingGraph;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  onCompose: (compose: Compose) => void;
  canWrite: boolean;
}) {
  const layout = useRoutingLayout(graph);
  const wrapper = useRef<HTMLDivElement>(null);

  // How much room is left below the canvas's own top edge. Measured rather than
  // assumed, because every banner above it is conditional; without this the graph
  // runs off the bottom of the screen on exactly the clusters that have something
  // worth showing in a banner.
  useEffect(() => {
    const el = wrapper.current;
    if (!el) return;
    const measure = () => {
      const top = el.getBoundingClientRect().top;
      el.style.setProperty('--as-routing-top', `${Math.round(top)}px`);
    };
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, []);

  const [focusedId, setFocusedId] = useState<string | null>(null);
  const elements = useRef(new Map<string, HTMLElement>());
  const entry = useRef<HTMLButtonElement>(null);

  const model = useMemo(
    () => toReactFlow(graph, layout.positions, selectedId),
    [graph, layout.positions, selectedId],
  );

  /** Reading order: left to right, then down — the order the layout put them in. */
  const order = useMemo(
    () =>
      [...model.nodes]
        .sort((a, b) => a.position.x - b.position.x || a.position.y - b.position.y)
        .map((n) => n.id),
    [model.nodes],
  );

  // The tab stop must always be on an element that is still drawn, or Tab falls
  // through the canvas entirely after a bound or a filter changes what it holds.
  useEffect(() => {
    setFocusedId((was) => (was && order.includes(was) ? was : (order[0] ?? null)));
  }, [order]);

  const register = useCallback((id: string, el: HTMLElement | null) => {
    if (el) elements.current.set(id, el);
    else elements.current.delete(id);
  }, []);

  const context = useMemo<RoutingCanvasState>(
    () => ({ focusedId, register, focus: setFocusedId, select: onSelect }),
    [focusedId, register, onSelect],
  );

  const moveTo = (id: string | undefined) => {
    if (!id) return;
    setFocusedId(id);
    elements.current.get(id)?.focus();
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onSelect(null);
      entry.current?.focus();
      return;
    }
    const step = ['ArrowRight', 'ArrowDown'].includes(event.key)
      ? 1
      : ['ArrowLeft', 'ArrowUp'].includes(event.key)
        ? -1
        : 0;
    if (step === 0) return;
    event.preventDefault();
    const at = focusedId ? order.indexOf(focusedId) : -1;
    moveTo(order[(at + step + order.length) % order.length]);
  };

  const connect = (connection: Connection) => {
    const from = graph.nodes.find((n) => n.id === connection.source);
    const to = graph.nodes.find((n) => n.id === connection.target);
    if (!from || !to || from.id === to.id) return;
    if (from.kind === 'address' && to.kind === 'address') {
      onCompose({ kind: 'divert', address: from.name, forwardingAddress: to.name });
    } else if (from.kind === 'queue' && to.kind === 'target') {
      onCompose({ kind: 'bridge', queueName: from.name, forwardingAddress: to.name });
    }
  };

  const laidOut = Object.keys(layout.positions).length > 0 || graph.nodes.length === 0;

  return (
    <div>
      {layout.error ? (
        <Alert color="red" variant="light" title="The graph could not be laid out" mb="xs">
          {layout.error} Every element it would have drawn is on the Declared &amp; live tab.
        </Alert>
      ) : null}

      <Button
        ref={entry}
        variant="default"
        size="xs"
        mb="xs"
        onClick={() => moveTo(focusedId ?? order[0])}
        disabled={order.length === 0}
      >
        Enter the routing graph
      </Button>

      <div
        ref={wrapper}
        className={classes.wrapper}
        role="group"
        aria-label={`Routing graph, ${graph.nodes.length} element${graph.nodes.length === 1 ? '' : 's'}`}
        onKeyDown={onKeyDown}
      >
        {!laidOut ? (
          <div className={classes.overlay} aria-busy="true" aria-label="Laying out the graph">
            <Loader size="sm" />
          </div>
        ) : null}
        <RoutingCanvasContext.Provider value={context}>
          <ReactFlowProvider>
            <ReactFlow
              nodes={model.nodes}
              edges={model.edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              minZoom={0.2}
              maxZoom={1.6}
              nodesDraggable={false}
              nodesConnectable={canWrite}
              nodesFocusable={false}
              edgesFocusable={false}
              elementsSelectable={false}
              onlyRenderVisibleElements={model.nodes.length > DENSE}
              onConnect={connect}
              // React Flow gives a node `pointer-events: none` unless it is
              // selectable, draggable or has a click handler, and this canvas
              // owns its own selection and tab stop rather than React Flow's —
              // so without this the element inside it could not be clicked at all.
              onNodeClick={(_, node) => onSelect(node.id)}
              onPaneClick={() => onSelect(null)}
              proOptions={{ hideAttribution: true }}
            >
              <Background gap={24} />
              <Controls showInteractive={false} />
              <FitOnLayout signature={layout.pending ? null : layoutSignature(graph)} />
            </ReactFlow>
          </ReactFlowProvider>
        </RoutingCanvasContext.Provider>
      </div>

      <Text component="p" className={classes.legend}>
        <span>Arrow keys move between elements; Enter opens one; Escape leaves the graph.</span>
        <span>Shapes: {Object.values(KIND_WORDS).join(' · ')}.</span>
        {canWrite ? <span>Drag address to address to propose a divert, queue to target to propose a bridge.</span> : null}
        <span>Nothing here is written to a broker until a saved revision is applied.</span>
      </Text>
    </div>
  );
}

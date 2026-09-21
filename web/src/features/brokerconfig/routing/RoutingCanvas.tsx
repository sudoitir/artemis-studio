import { forwardRef, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  Background,
  BackgroundVariant,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type Edge,
} from '@xyflow/react';
import { ActionIcon, Alert, Button, Loader, Text, Tooltip } from '@mantine/core';
import { IconFocusCentered, IconZoomIn, IconZoomOut } from '@tabler/icons-react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import { runLayout as runElkLayout } from '../../../ui/graph/elk.ts';
import { RoutingCanvasContext, type RoutingCanvasState } from './canvasContext.ts';
import { composes, type RoutingGraph } from './routingGraph.ts';
import {
  layoutSignature,
  neighbour,
  positionsFrom,
  readingOrder,
  toElkGraph,
  toReactFlow,
  type Direction,
  type Positions,
} from './routingLayout.ts';
import { RoutingEdge } from './RoutingEdge.tsx';
import { AddressNode, BridgeNode, DivertNode, QueueNode, TargetNode } from './RoutingNodes.tsx';
import classes from './RoutingCanvas.module.css';

const nodeTypes = { address: AddressNode, queue: QueueNode, divert: DivertNode, bridge: BridgeNode, target: TargetNode };
const edgeTypes = { routing: RoutingEdge };

/** Above this many drawn elements React Flow renders only what is on screen (ADR-0056). */
const DENSE = 60;

/** Generous gutters, and never larger than life: a small graph is not blown up to fill the frame. */
const FIT = { padding: 0.16, maxZoom: 1 };
const ZOOM = { duration: 140 };

/** The zoom a keyboard-focused element is brought to: life size, where its words can be read. */
const READABLE = 1;

const DIRECTION: Record<string, Direction> = {
  ArrowRight: 'right',
  ArrowLeft: 'left',
  ArrowDown: 'down',
  ArrowUp: 'up',
};

/**
 * The toolbar over the canvas: the keyboard's way in, the view controls, and whatever the
 * builder adds at its end. It sits inside the flow provider so the view controls reach the
 * viewport; motion on them is skipped when the operator asks for reduced motion.
 */
const CanvasToolbar = forwardRef<
  HTMLButtonElement,
  { onEnter: () => void; canEnter: boolean; leading?: ReactNode; actions?: ReactNode }
>(function CanvasToolbar({ onEnter, canEnter, leading, actions }, entry) {
  const flow = useReactFlow();
  const reduced = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  const zoom = reduced ? { duration: 0 } : ZOOM;
  const view = (label: string, icon: ReactNode, onClick: () => void) => (
    <Tooltip label={label} withArrow openDelay={300}>
      <ActionIcon variant="subtle" color="gray" size="md" aria-label={label} onClick={onClick}>
        {icon}
      </ActionIcon>
    </Tooltip>
  );
  return (
    <div className={classes.toolbar} role="toolbar" aria-label="Routing builder">
      <Button ref={entry} variant="default" size="xs" onClick={onEnter} disabled={!canEnter}>
        Enter the routing graph
      </Button>
      <span className={classes.divider} aria-hidden="true" />
      <div className={classes.toolbarGroup}>
        {view('Zoom out', <IconZoomOut size={16} stroke={1.75} />, () => void flow.zoomOut(zoom))}
        {view('Zoom in', <IconZoomIn size={16} stroke={1.75} />, () => void flow.zoomIn(zoom))}
        {view('Fit the graph to the view', <IconFocusCentered size={16} stroke={1.75} />, () =>
          void flow.fitView({ ...FIT, ...zoom }),
        )}
      </div>
      {leading}
      <span className={classes.toolbarSpacer} />
      {actions}
    </div>
  );
});

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
    const fit = () => void flow.fitView(FIT);
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
 * Brings the element the keyboard moved to into view at a readable size. Without it, entering a
 * dense graph fitted to the frame focuses a card too small to read, and an arrow key can move the
 * focus somewhere off the frame entirely. It jumps rather than glides: nothing on this canvas
 * animates (ADR-0090 D5).
 */
function FollowFocus({
  follow,
  frame,
}: {
  follow: React.MutableRefObject<((id: string) => void) | null>;
  frame: React.RefObject<HTMLDivElement | null>;
}) {
  const flow = useReactFlow();
  useEffect(() => {
    follow.current = (id) => {
      const node = flow.getNode(id);
      const box = frame.current;
      if (!node || !box) return;
      const { x, y, zoom } = flow.getViewport();
      const width = node.width ?? 0;
      const height = node.height ?? 0;
      const left = node.position.x * zoom + x;
      const top = node.position.y * zoom + y;
      const inView =
        left >= 0 && top >= 0 && left + width * zoom <= box.clientWidth && top + height * zoom <= box.clientHeight;
      if (inView && zoom >= READABLE) return;
      void flow.setCenter(node.position.x + width / 2, node.position.y + height / 2, {
        zoom: Math.max(zoom, READABLE),
        duration: 0,
      });
    };
    return () => {
      follow.current = null;
    };
  }, [flow, follow, frame]);
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
  leading,
  actions,
}: {
  graph: RoutingGraph;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  onCompose: (compose: Compose) => void;
  canWrite: boolean;
  /** Toolbar controls after the view controls — the region picker, when the graph is bounded. */
  leading?: ReactNode;
  /** Toolbar controls at its end — the builder's authoring and apply actions. */
  actions?: ReactNode;
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

  /** Reading order: left to right, then down. Entry lands on its first element. */
  const order = useMemo(() => readingOrder(model.nodes), [model.nodes]);

  // The tab stop must always be on an element that is still drawn, or Tab falls
  // through the canvas entirely after a bound or a filter changes what it holds.
  useEffect(() => {
    setFocusedId((was) => (was && order.includes(was) ? was : (order[0] ?? null)));
  }, [order]);

  // An element brought into view may not be drawn yet on a dense canvas, which draws only what is
  // visible; its focus waits here until it registers.
  const pendingFocus = useRef<string | null>(null);
  const follow = useRef<((id: string) => void) | null>(null);

  const register = useCallback((id: string, el: HTMLElement | null) => {
    if (!el) {
      elements.current.delete(id);
      return;
    }
    elements.current.set(id, el);
    if (pendingFocus.current === id) {
      pendingFocus.current = null;
      // The canvas owns the view; the browser scrolling its clipped frame would shift the drawing.
      el.focus({ preventScroll: true });
    }
  }, []);

  const context = useMemo<RoutingCanvasState>(
    () => ({ focusedId, canWrite, register, focus: setFocusedId, select: onSelect }),
    [focusedId, canWrite, register, onSelect],
  );

  const moveTo = (id: string | null | undefined) => {
    if (!id) return;
    setFocusedId(id);
    follow.current?.(id);
    const el = elements.current.get(id);
    if (el) el.focus({ preventScroll: true });
    else pendingFocus.current = id;
  };

  /** Entering lands on an element and opens it in the inspector, so entering visibly does something. */
  const enter = () => {
    const id = focusedId ?? order[0];
    if (!id) return;
    moveTo(id);
    onSelect(id);
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onSelect(null);
      entry.current?.focus();
      return;
    }
    const direction = DIRECTION[event.key];
    if (!direction) return;
    event.preventDefault();
    moveTo(focusedId ? neighbour(model.nodes, focusedId, direction) : order[0]);
  };

  /** The two ends of a drag, when they compose something; the one rule for both validation and composing. */
  const ends = (connection: Connection | Edge) => {
    const from = graph.nodes.find((n) => n.id === connection.source);
    const to = graph.nodes.find((n) => n.id === connection.target);
    if (!from || !to || from.id === to.id) return null;
    const what = composes(from.kind, to.kind);
    return what ? { what, from, to } : null;
  };

  const connect = (connection: Connection) => {
    const pair = ends(connection);
    if (pair?.what === 'divert') {
      onCompose({ kind: 'divert', address: pair.from.name, forwardingAddress: pair.to.name });
    } else if (pair?.what === 'bridge') {
      onCompose({ kind: 'bridge', queueName: pair.from.name, forwardingAddress: pair.to.name });
    }
  };

  const laidOut = Object.keys(layout.positions).length > 0 || graph.nodes.length === 0;

  return (
    <div>
      {layout.error ? (
        <Alert color="red" variant="light" title="The graph could not be laid out" mb="xs">
          {layout.error} Every element it would have drawn is on the Configuration screen's Declared &amp; live tab.
        </Alert>
      ) : null}

      <ReactFlowProvider>
        <div className={classes.frame}>
          <CanvasToolbar
            ref={entry}
            onEnter={enter}
            canEnter={order.length > 0}
            leading={leading}
            actions={actions}
          />
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
                isValidConnection={(connection) => ends(connection) !== null}
                // A line lands on the nearest end that would take it, not only on a pixel-exact drop.
                connectionRadius={48}
                // React Flow gives a node `pointer-events: none` unless it is
                // selectable, draggable or has a click handler, and this canvas
                // owns its own selection and tab stop rather than React Flow's —
                // so without this the element inside it could not be clicked at all.
                onNodeClick={(_, node) => onSelect(node.id)}
                onPaneClick={() => onSelect(null)}
                proOptions={{ hideAttribution: true }}
              >
                <Background variant={BackgroundVariant.Dots} gap={20} size={1.2} patternClassName={classes.dots} />
                <FitOnLayout signature={layout.pending ? null : layoutSignature(graph)} />
                <FollowFocus follow={follow} frame={wrapper} />
              </ReactFlow>
            </RoutingCanvasContext.Provider>
          </div>
        </div>
      </ReactFlowProvider>

      <Text component="p" className={classes.legend}>
        <span>Arrow keys move between elements; Enter opens one; Escape leaves the graph.</span>
        {canWrite ? <span>Drag address to address to propose a divert, queue to target to propose a bridge.</span> : null}
        <span>Nothing here is written to a broker until a saved revision is applied.</span>
      </Text>
    </div>
  );
}

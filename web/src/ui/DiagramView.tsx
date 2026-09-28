import { createContext, memo, useContext, useEffect, useMemo, useRef, useState } from 'react';
import {
  Background,
  BackgroundVariant,
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import { ActionIcon, Alert, Loader, Tooltip } from '@mantine/core';
import { IconFocusCentered } from '@tabler/icons-react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import { runLayout } from './graph/elk.ts';
import { layoutSignature, nodeName, type DiagramEdge, type DiagramNode } from './diagram.ts';
import classes from './DiagramView.module.css';

export type { DiagramEdge, DiagramNode };

export interface DiagramViewProps {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  selectedId?: string | null;
  onSelect?: (id: string) => void;
  /** Which way the arrows run. Default: down. */
  direction?: 'DOWN' | 'RIGHT';
  /** The frame's height in pixels. Default 480. */
  height?: number;
  /** Names the diagram for screen readers. */
  'aria-label': string;
}

const CARD = { width: 240, height: 68 };
const FIT = { padding: 0.12, maxZoom: 1 };

type Positions = Record<string, { x: number; y: number }>;

function toElk(nodes: DiagramNode[], edges: DiagramEdge[], direction: string): ElkNode {
  const ids = new Set(nodes.map((n) => n.id));
  return {
    id: 'diagram',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': direction,
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.layered.spacing.nodeNodeBetweenLayers': '56',
      'elk.spacing.nodeNode': '32',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
    },
    children: nodes.map((n) => ({ id: n.id, ...CARD })),
    edges: edges
      .filter((e) => ids.has(e.source) && ids.has(e.target))
      .map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };
}

function useLayout(nodes: DiagramNode[], edges: DiagramEdge[], direction: string) {
  const signature = layoutSignature(nodes, edges, direction);
  const latest = useRef({ nodes, edges });
  latest.current = { nodes, edges };
  const [state, setState] = useState<{ signature: string; positions: Positions }>({ signature: '', positions: {} });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    runLayout(toElk(latest.current.nodes, latest.current.edges, direction))
      .then((laidOut) => {
        if (cancelled) return;
        const positions: Positions = {};
        for (const c of laidOut.children ?? []) positions[c.id] = { x: c.x ?? 0, y: c.y ?? 0 };
        setState({ signature, positions });
        setError(null);
      })
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
    // The signature holds everything the layout depends on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);
  return { positions: state.positions, ready: state.signature === signature, error, signature };
}

interface Roving {
  tabStop: string | null;
  register: (id: string, el: HTMLButtonElement | null) => void;
  focus: (id: string) => void;
  select: (id: string) => void;
}
const RovingContext = createContext<Roving>({ tabStop: null, register: () => {}, focus: () => {}, select: () => {} });

type CardData = { node: DiagramNode; name: string; selected: boolean; vertical: boolean };

const Card = memo(function Card({ id, data }: NodeProps<Node<CardData>>) {
  const { tabStop, register, focus, select } = useContext(RovingContext);
  const n = data.node;
  return (
    <>
      <Handle type="target" position={data.vertical ? Position.Top : Position.Left} className={classes.handle} isConnectable={false} />
      <button
        ref={(el) => register(id, el)}
        type="button"
        className={classes.node}
        data-state={n.state}
        data-selected={data.selected || undefined}
        tabIndex={tabStop === id ? 0 : -1}
        aria-label={data.name}
        aria-pressed={data.selected}
        title={data.name}
        onFocus={() => focus(id)}
        onClick={() => select(id)}
      >
        {n.kind || n.state ? (
          <span className={classes.kind}>
            {n.kind}
            {n.state ? <span className={classes.state}>{n.state === 'error' ? 'Invalid' : 'Warning'}</span> : null}
          </span>
        ) : null}
        <span className={classes.label}>{n.label}</span>
        {n.detail ? <span className={classes.detail}>{n.detail}</span> : null}
      </button>
      <Handle type="source" position={data.vertical ? Position.Bottom : Position.Right} className={classes.handle} isConnectable={false} />
    </>
  );
});

/** A line with its label as a chip. The label is also in the target's accessible name, so the chip is hidden from it. */
const Line = memo(function Line({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, markerEnd, label, data }: EdgeProps) {
  const [path, labelX, labelY] = getSmoothStepPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition, borderRadius: 8 });
  return (
    <>
      <BaseEdge id={id} path={path} className={(data as { dashed?: boolean })?.dashed ? classes.dashed : classes.edge} markerEnd={markerEnd} interactionWidth={0} />
      {label ? (
        <EdgeLabelRenderer>
          <div
            className={`${classes.chip} nodrag nopan`}
            aria-hidden="true"
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  );
});

const nodeTypes = { card: Card };
const edgeTypes = { line: Line };

function FitOnLayout({ signature }: { signature: string | null }) {
  const flow = useReactFlow();
  useEffect(() => {
    if (!signature) return;
    const frame = requestAnimationFrame(() => void flow.fitView(FIT));
    return () => cancelAnimationFrame(frame);
  }, [flow, signature]);
  return null;
}

function FitButton() {
  const flow = useReactFlow();
  return (
    <Tooltip label="Fit the diagram to the view" withArrow openDelay={300}>
      <ActionIcon
        className={classes.fit}
        variant="default"
        size="md"
        aria-label="Fit the diagram to the view"
        onClick={() => void flow.fitView({ ...FIT, duration: 0 })}
      >
        <IconFocusCentered size={16} stroke={1.75} />
      </ActionIcon>
    </Tooltip>
  );
}

/**
 * A read-only diagram of boxes and arrows, laid out automatically (ELK layered, in a worker) and
 * drawn in Studio's theme (ADR-0117). Nothing is dragged, connected or deleted: it shows a
 * structure and reports which box was chosen.
 *
 * <p>The diagram is one tab stop. Arrow keys move between boxes in reading order, Enter or Space
 * selects one, and the selection is announced. A box with a problem says so in words, on the box
 * and in its accessible name, so colour is never the only signal. Positions change only when the
 * boxes or arrows do, never when a label does.
 */
export function DiagramView({
  nodes,
  edges,
  selectedId = null,
  onSelect,
  direction = 'DOWN',
  height = 480,
  'aria-label': ariaLabel,
}: DiagramViewProps) {
  const layout = useLayout(nodes, edges, direction);
  const vertical = direction === 'DOWN';
  const order = useMemo(
    () =>
      [...nodes]
        .filter((n) => layout.positions[n.id])
        .sort((a, b) => {
          const pa = layout.positions[a.id];
          const pb = layout.positions[b.id];
          return vertical ? pa.y - pb.y || pa.x - pb.x : pa.x - pb.x || pa.y - pb.y;
        })
        .map((n) => n.id),
    [nodes, layout.positions, vertical],
  );
  const [focused, setFocused] = useState<string | null>(null);
  const tabStop = focused && order.includes(focused) ? focused : selectedId && order.includes(selectedId) ? selectedId : (order[0] ?? null);
  const elements = useRef(new Map<string, HTMLButtonElement>());
  const [announce, setAnnounce] = useState('');

  const roving = useMemo<Roving>(
    () => ({
      tabStop,
      register: (id, el) => {
        if (el) elements.current.set(id, el);
        else elements.current.delete(id);
      },
      focus: setFocused,
      select: (id) => {
        const n = nodes.find((x) => x.id === id);
        if (n) setAnnounce(`Selected ${nodeName(n)}`);
        onSelect?.(id);
      },
    }),
    [tabStop, nodes, onSelect],
  );

  const onKeyDown = (e: React.KeyboardEvent) => {
    const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[e.key];
    if (!step || !tabStop) return;
    e.preventDefault();
    const next = order[Math.min(order.length - 1, Math.max(0, order.indexOf(tabStop) + step))];
    setFocused(next);
    elements.current.get(next)?.focus({ preventScroll: true });
  };

  const model = useMemo(() => {
    const rfNodes: Node<CardData>[] = nodes
      .filter((n) => layout.positions[n.id])
      .map((n) => ({
        id: n.id,
        type: 'card',
        position: layout.positions[n.id],
        data: {
          node: n,
          name: nodeName(n, edges.filter((e) => e.target === n.id && e.label).map((e) => e.label as string)),
          selected: n.id === selectedId,
          vertical,
        },
        ...CARD,
      }));
    const rfEdges: Edge[] = edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      type: 'line',
      label: e.label,
      data: { dashed: e.dashed },
      markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
      focusable: false,
    }));
    return { nodes: rfNodes, edges: rfEdges };
  }, [nodes, edges, layout.positions, selectedId, vertical]);

  if (layout.error) {
    return (
      <Alert color="red" variant="light" title="The diagram could not be laid out">
        {layout.error}
      </Alert>
    );
  }

  return (
    <ReactFlowProvider>
      <div className={classes.frame} style={{ blockSize: height }} role="group" aria-label={ariaLabel} onKeyDown={onKeyDown}>
        {!layout.ready ? (
          <div className={classes.overlay} aria-busy="true" aria-label="Laying out the diagram">
            <Loader size="sm" />
          </div>
        ) : null}
        <RovingContext.Provider value={roving}>
          <ReactFlow
            nodes={model.nodes}
            edges={model.edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            minZoom={0.2}
            maxZoom={1.6}
            nodesDraggable={false}
            nodesConnectable={false}
            nodesFocusable={false}
            edgesFocusable={false}
            elementsSelectable={false}
            onNodeClick={(_, node) => roving.select(node.id)}
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={20} size={1.2} patternClassName={classes.dots} />
            <FitOnLayout signature={layout.ready ? layout.signature : null} />
          </ReactFlow>
        </RovingContext.Provider>
        <FitButton />
        <span className={classes.live} aria-live="polite">
          {announce}
        </span>
      </div>
    </ReactFlowProvider>
  );
}

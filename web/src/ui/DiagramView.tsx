import { createContext, Fragment, memo, useContext, useEffect, useMemo, useRef, useState } from 'react';
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
import { ActionIcon, Alert, Loader, Menu, Tooltip } from '@mantine/core';
import { IconDots, IconFocusCentered, IconPlus, IconZoomIn, IconZoomOut } from '@tabler/icons-react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import { ActionMenuItem } from './ActionMenuItem.tsx';
import { AnchoredMenu } from './AnchoredMenu.tsx';
import { runLayout } from './graph/elk.ts';
import { layoutSignature, nodeName, type DiagramAction, type DiagramChoice, type DiagramEdge, type DiagramNode } from './diagram.ts';
import classes from './DiagramView.module.css';
import { anchorBelow, clampToViewport, type MenuAnchor } from './menuAnchor.ts';

export type { DiagramAction, DiagramChoice, DiagramEdge, DiagramNode };

export interface DiagramViewProps {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  selectedId?: string | null;
  onSelect?: (id: string) => void;
  /** Which way the arrows run. Default: down. */
  direction?: 'DOWN' | 'RIGHT';
  /** The frame's height: pixels, or any CSS length such as '100%' to fill a sized parent. Default 480. */
  height?: number | string;
  /** Names the diagram for screen readers. */
  'aria-label': string;
  /** What can be inserted on an arrow marked `insertable`. With `onInsert`, each such arrow offers them. */
  insertChoices?: DiagramChoice[];
  /** A choice was made on an arrow. The diagram changes nothing itself. */
  onInsert?: (edgeId: string, value: string) => void;
  /** The actions a box offers in its menu. With `onNodeAction`, every box that has some offers them. */
  nodeActions?: (node: DiagramNode) => DiagramAction[];
  /** An action was chosen on a box. The diagram changes nothing itself. */
  onNodeAction?: (nodeId: string, actionId: string) => void;
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

/** A menu the diagram has open: a box's actions, or what can be inserted on the arrows into a box or on one arrow. */
type OpenMenu = { kind: 'actions'; nodeId: string; anchor: MenuAnchor; opener: HTMLElement } | { kind: 'insert'; edgeIds: string[]; anchor: MenuAnchor; opener: HTMLElement };

interface Roving {
  tabStop: string | null;
  register: (id: string, el: HTMLButtonElement | null) => void;
  focus: (id: string) => void;
  select: (id: string) => void;
  /** Opens a box's actions, or null when the diagram offers none. */
  openActions: ((id: string, anchor: MenuAnchor, opener: HTMLElement) => void) | null;
  /** Opens the insert choices of these arrows, or null when the diagram offers none. */
  openInsert: ((edgeIds: string[], anchor: MenuAnchor, opener: HTMLElement) => void) | null;
}
const RovingContext = createContext<Roving>({ tabStop: null, register: () => {}, focus: () => {}, select: () => {}, openActions: null, openInsert: null });

type CardData = {
  node: DiagramNode;
  name: string;
  selected: boolean;
  vertical: boolean;
  /** Whether the box has a menu of actions. */
  actions: boolean;
  /** The insertable arrows into the box, which Insert offers from the keyboard. */
  insertInto: string[];
};

const Card = memo(function Card({ id, data }: NodeProps<Node<CardData>>) {
  const { tabStop, register, focus, select, openActions, openInsert } = useContext(RovingContext);
  const n = data.node;
  const actions = data.actions && openActions ? openActions : null;
  const insert = data.insertInto.length && openInsert ? openInsert : null;
  // Shift+F10 and the menu key are followed by the browser's own contextmenu event, which would
  // reopen the menu at the pointer and close the one the keyboard opened.
  const suppressContextMenuUntil = useRef(0);
  const keys = [actions ? 'Shift+F10' : null, insert ? 'Insert' : null].filter(Boolean).join(' ');
  return (
    <div className={classes.card}>
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
        aria-keyshortcuts={keys || undefined}
        title={data.name}
        onFocus={() => focus(id)}
        onClick={() => select(id)}
        onContextMenu={
          actions
            ? (event) => {
                event.preventDefault();
                if (performance.now() < suppressContextMenuUntil.current) return;
                actions(id, clampToViewport({ x: event.clientX, y: event.clientY }), event.currentTarget);
              }
            : undefined
        }
        onKeyDown={(event) => {
          if (actions && ((event.key === 'F10' && event.shiftKey) || event.key === 'ContextMenu')) {
            event.preventDefault();
            suppressContextMenuUntil.current = performance.now() + 500;
            actions(id, anchorBelow(event.currentTarget), event.currentTarget);
          } else if (insert && (event.key === 'Insert' || event.key === '+')) {
            event.preventDefault();
            insert(data.insertInto, anchorBelow(event.currentTarget), event.currentTarget);
          }
        }}
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
      {actions ? (
        // The keyboard opens the same menu with Shift+F10 on the box, so this stays out of the tab order.
        <ActionIcon
          className={`${classes.more} nodrag nopan`}
          variant="subtle"
          size="sm"
          tabIndex={-1}
          aria-label={`Actions for ${n.label}`}
          aria-haspopup="menu"
          data-selected={data.selected || undefined}
          onClick={(event) => {
            event.stopPropagation();
            actions(id, anchorBelow(event.currentTarget), event.currentTarget);
          }}
        >
          <IconDots size={14} stroke={1.75} />
        </ActionIcon>
      ) : null}
      <Handle type="source" position={data.vertical ? Position.Bottom : Position.Right} className={classes.handle} isConnectable={false} />
    </div>
  );
});

type LineData = { dashed?: boolean; insert?: string };

/**
 * A line with its label as a chip. The label is also in the target's accessible name, so the chip is
 * hidden from it. An insertable line carries a "+" beside the chip, named after the boxes it joins.
 */
const Line = memo(function Line({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, markerEnd, label, data }: EdgeProps) {
  const { openInsert } = useContext(RovingContext);
  const [path, labelX, labelY] = getSmoothStepPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition, borderRadius: 8 });
  const d = (data ?? {}) as LineData;
  const insert = d.insert && openInsert ? openInsert : null;
  return (
    <>
      <BaseEdge id={id} path={path} className={d.dashed ? classes.dashed : classes.edge} markerEnd={markerEnd} interactionWidth={0} />
      {label || insert ? (
        <EdgeLabelRenderer>
          <div className={`${classes.labels} nodrag nopan`} style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}>
            {label ? (
              <span className={classes.chip} aria-hidden="true">
                {label}
              </span>
            ) : null}
            {insert ? (
              // The keyboard reaches the same choices with Insert on the box this line leads to.
              <ActionIcon
                className={classes.insert}
                variant="default"
                radius="xl"
                size="sm"
                tabIndex={-1}
                aria-label={d.insert}
                aria-haspopup="menu"
                onClick={(event) => insert([id], anchorBelow(event.currentTarget), event.currentTarget)}
              >
                <IconPlus size={12} stroke={2} />
              </ActionIcon>
            ) : null}
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

/** Zoom in, zoom out and fit, without animation so reduced motion needs nothing more. */
function ViewControls() {
  const flow = useReactFlow();
  const controls = [
    { label: 'Zoom in', icon: IconZoomIn, run: () => void flow.zoomIn({ duration: 0 }) },
    { label: 'Zoom out', icon: IconZoomOut, run: () => void flow.zoomOut({ duration: 0 }) },
    { label: 'Fit the diagram to the view', icon: IconFocusCentered, run: () => void flow.fitView({ ...FIT, duration: 0 }) },
  ];
  return (
    <ActionIcon.Group orientation="vertical" className={classes.controls}>
      {controls.map((c) => (
        <Tooltip key={c.label} label={c.label} position="left" withArrow openDelay={300}>
          <ActionIcon variant="default" size="md" aria-label={c.label} onClick={c.run}>
            <c.icon size={16} stroke={1.75} />
          </ActionIcon>
        </Tooltip>
      ))}
    </ActionIcon.Group>
  );
}

/**
 * A diagram of boxes and arrows, laid out automatically (ELK layered, in a worker) and drawn in
 * Studio's theme (ADR-0117). Nothing is dragged, connected or deleted: it shows a structure and
 * reports which box was chosen. A caller may offer things to insert on arrows and actions on boxes
 * (ADR-0120); the diagram reports the choice and the caller changes the nodes.
 *
 * <p>The diagram is one tab stop. Arrow keys move between boxes in reading order, Enter or Space
 * selects one, and the selection is announced. A box with a problem says so in words, on the box
 * and in its accessible name, so colour is never the only signal. Positions change only when the
 * boxes or arrows do, never when a label does. A box's actions open from its "⋯", a right-click,
 * or Shift+F10; Insert on a box offers what can go on the arrows into it.
 */
export function DiagramView({
  nodes,
  edges,
  selectedId = null,
  onSelect,
  direction = 'DOWN',
  height = 480,
  'aria-label': ariaLabel,
  insertChoices,
  onInsert,
  nodeActions,
  onNodeAction,
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
  const [menu, setMenu] = useState<OpenMenu | null>(null);
  const inserting = !!(onInsert && insertChoices?.length);
  const acting = !!(onNodeAction && nodeActions);
  const actionsOf = useMemo(() => {
    const byId = new Map<string, DiagramAction[]>();
    if (acting) for (const n of nodes) byId.set(n.id, nodeActions(n));
    return byId;
  }, [acting, nodes, nodeActions]);

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
      openActions: acting ? (nodeId, anchor, opener) => setMenu({ kind: 'actions', nodeId, anchor, opener }) : null,
      openInsert: inserting ? (edgeIds, anchor, opener) => setMenu({ kind: 'insert', edgeIds, anchor, opener }) : null,
    }),
    [tabStop, nodes, onSelect, acting, inserting],
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
          actions: (actionsOf.get(n.id)?.length ?? 0) > 0,
          insertInto: inserting ? edges.filter((e) => e.insertable && e.target === n.id).map((e) => e.id) : [],
        },
        ...CARD,
      }));
    const rfEdges: Edge[] = edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      type: 'line',
      label: e.label,
      data: { dashed: e.dashed, insert: inserting && e.insertable ? insertName(e, nodes) : undefined },
      markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
      focusable: false,
    }));
    return { nodes: rfNodes, edges: rfEdges };
  }, [nodes, edges, layout.positions, selectedId, vertical, actionsOf, inserting]);

  const closeMenu = () => {
    const opener = menu?.opener;
    setMenu(null);
    if (opener?.isConnected) opener.focus();
  };
  const menuNode = menu?.kind === 'actions' ? nodes.find((n) => n.id === menu.nodeId) : undefined;
  const menuEdges = menu?.kind === 'insert' ? edges.filter((e) => menu.edgeIds.includes(e.id)) : [];

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
            // A menu is anchored to a point on screen; panning moves the box away from it. Only the
            // user's own pans count: fitting after a layout has no event, and must not close a menu.
            onMoveStart={(event) => {
              if (event) setMenu(null);
            }}
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={20} size={1.2} patternClassName={classes.dots} />
            <FitOnLayout signature={layout.ready ? layout.signature : null} />
          </ReactFlow>
        </RovingContext.Provider>
        <ViewControls />
        <span className={classes.live} aria-live="polite">
          {announce}
        </span>
      </div>
      <AnchoredMenu
        opened={menu !== null}
        anchor={menu?.anchor ?? null}
        label={menuNode ? `Actions for ${menuNode.label}` : menuEdges.length === 1 ? insertName(menuEdges[0], nodes) : 'Insert'}
        onClose={closeMenu}
      >
        {menuNode
          ? (actionsOf.get(menuNode.id) ?? []).map((a) => (
              <ActionMenuItem
                key={a.id}
                label={a.label}
                tone={a.danger ? 'danger' : undefined}
                verdict={a.disabledReason ? { kind: 'blocked', reason: a.disabledReason } : undefined}
                onSelect={() => {
                  closeMenu();
                  onNodeAction?.(menuNode.id, a.id);
                }}
              />
            ))
          : menuEdges.map((e) => (
              <Fragment key={e.id}>
                {menuEdges.length > 1 ? <Menu.Label>{insertName(e, nodes)}</Menu.Label> : null}
                {(insertChoices ?? []).map((c, i, all) => (
                  <Fragment key={c.value}>
                    {c.group && c.group !== all[i - 1]?.group ? <Menu.Label>{c.group}</Menu.Label> : null}
                    <Menu.Item
                      onClick={() => {
                        closeMenu();
                        onInsert?.(e.id, c.value);
                      }}
                    >
                      {c.label}
                    </Menu.Item>
                  </Fragment>
                ))}
              </Fragment>
            ))}
      </AnchoredMenu>
    </ReactFlowProvider>
  );
}

/** "Insert between reserve and charge": what an insert control does, in the boxes' own words. */
function insertName(e: DiagramEdge, nodes: DiagramNode[]): string {
  const label = (id: string) => nodes.find((n) => n.id === id)?.label ?? id;
  return `Insert between ${label(e.source)} and ${label(e.target)}`;
}

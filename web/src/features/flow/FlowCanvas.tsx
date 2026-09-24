import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Background,
  Controls,
  MiniMap,
  Panel,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
  useStoreApi,
} from '@xyflow/react';
import { Alert, Loader, Text } from '@mantine/core';
import { useReducedMotion } from '@mantine/hooks';

import { AnchoredMenu } from '../../ui/AnchoredMenu.tsx';
import type { MenuAnchor } from '../../ui/menuAnchor.ts';
import type { FlowGraphView } from './api.ts';
import { FlowCanvasContext, type FlowCanvasState } from './canvasContext.ts';
import { allocateDots } from './edgeEncoding.ts';
import { FlowEdge } from './FlowEdge.tsx';
import { DENSE_NODES, layoutSignature, pathThrough, toReactFlow } from './flowLayout.ts';
import { FlowLegend } from './FlowLegend.tsx';
import { hasActions } from './flowSearch.ts';
import { FlowNodeActions } from './rowActions.tsx';
import { AddressNode, ClientNode, LaneNode, QueueNode, RemoteNode } from './FlowNodes.tsx';
import { useFlowLayout } from './useFlowLayout.ts';
import classes from './FlowCanvas.module.css';

const nodeTypes = { client: ClientNode, address: AddressNode, queue: QueueNode, remote: RemoteNode, lane: LaneNode };
const edgeTypes = { flow: FlowEdge };

/** Labels and dots below this zoom would be unreadable specks; widths still carry the rate. */
const TEXT_ZOOM = 0.55;

function ZoomDetail({ onChange }: { onChange: (showText: boolean) => void }) {
  const showText = useStore((s) => s.transform[2] >= TEXT_ZOOM);
  useEffect(() => onChange(showText), [showText, onChange]);
  return null;
}

/** The smallest zoom a fresh view opens at: above TEXT_ZOOM, so rates and dots are drawn. */
const OPEN_ZOOM = 0.7;
const MARGIN = 24;

const MINIMAP_KEY = 'artemis-studio.flow.minimap';

/**
 * The overview map, in the product's own colours and foldable away.
 *
 * <p>React Flow draws the minimap with its own light palette, which on a dark
 * canvas is a white rectangle sitting over the graph — the brightest thing on
 * the screen, for the least important. It is painted from the flow tokens
 * instead, and on a graph the size of a real estate it is often in the way, so
 * it folds to its own button. The choice is per viewer and survives a reload;
 * browser storage can throw, and a minimap that will not fold is a smaller
 * problem than a canvas that will not render.
 */
function FlowMiniMap() {
  const [open, setOpen] = useState(() => {
    try {
      return window.localStorage.getItem(MINIMAP_KEY) !== 'off';
    } catch {
      return true;
    }
  });

  const toggle = () => {
    setOpen((was) => {
      const next = !was;
      try {
        window.localStorage.setItem(MINIMAP_KEY, next ? 'on' : 'off');
      } catch {
        /* a preference that cannot be stored is still honoured for this session */
      }
      return next;
    });
  };

  return (
    <>
      <Panel position="bottom-right" className={classes.minimapToggle}>
        <button type="button" onClick={toggle} aria-expanded={open} className={classes.minimapButton}>
          {open ? 'Hide overview' : 'Show overview'}
        </button>
      </Panel>
      {open ? (
        <MiniMap
          pannable
          zoomable
          className={classes.minimap}
          nodeClassName={classes.minimapNode}
          maskColor="var(--as-flow-minimap-mask)"
          maskStrokeColor="var(--as-border)"
          bgColor="transparent"
          ariaLabel="Overview of the whole graph"
        />
      ) : null}
    </>
  );
}

/**
 * Fit once per layout: a new set of nodes gets a fresh view, a rate-only refresh keeps the operator's.
 * A graph too large to fit legibly opens at a readable zoom, from its first row, rather than shrunk to
 * specks; the minimap and panning reach the rest.
 */
function RefitOnLayout({ signature }: { signature: string | null }) {
  const flow = useReactFlow();
  const store = useStoreApi();
  useEffect(() => {
    if (!signature) return;
    // Read the size when fitting, not as a dependency: opening the inspector narrows the canvas, and
    // that must not throw away the operator's pan and zoom.
    const frame = requestAnimationFrame(() => {
      const { width, height } = store.getState();
      if (width === 0 || height === 0) return;
      const bounds = flow.getNodesBounds(flow.getNodes());
      const fit = Math.min(width / (bounds.width + 2 * MARGIN), height / (bounds.height + 2 * MARGIN));
      if (fit >= OPEN_ZOOM) {
        void flow.fitView({ padding: 0.12, maxZoom: 1 });
        return;
      }
      const zoom = Math.min(1, Math.max(OPEN_ZOOM, width / (bounds.width + 2 * MARGIN)));
      const x = Math.max(MARGIN, (width - bounds.width * zoom) / 2) - bounds.x * zoom;
      void flow.setViewport({ x, y: MARGIN - bounds.y * zoom, zoom });
    });
    return () => cancelAnimationFrame(frame);
  }, [flow, store, signature]);
  return null;
}

/**
 * A selection the operator cannot see — restored from the address, or chosen by keyboard or from the
 * pane — is brought into view at the current zoom. The rest of the graph dims around a selection, so
 * one off-screen would leave nothing bright to look at.
 */
function RevealSelected({ id, ready }: { id: string | null; ready: boolean }) {
  const flow = useReactFlow();
  const store = useStoreApi();
  const reducedMotion = useReducedMotion();
  useEffect(() => {
    if (!id || !ready) return;
    const frame = requestAnimationFrame(() => {
      const node = flow.getInternalNode(id);
      if (!node) return;
      const { width, height, transform } = store.getState();
      const [tx, ty, zoom] = transform;
      const { x, y } = node.internals.positionAbsolute;
      const w = node.measured.width ?? 0;
      const h = node.measured.height ?? 0;
      const left = x * zoom + tx;
      const top = y * zoom + ty;
      if (left >= 0 && top >= 0 && left + w * zoom <= width && top + h * zoom <= height) return;
      void flow.setCenter(x + w / 2, y + h / 2, { zoom, duration: reducedMotion ? 0 : 300 });
    });
    return () => cancelAnimationFrame(frame);
  }, [flow, store, id, ready, reducedMotion]);
  return null;
}

/**
 * The flow graph (flow-visualization spec, ADR-0080): four columns laid out by ELK in a worker,
 * rates as width, labels and moving dots, faults in words, and the path through whatever is hovered,
 * focused or selected emphasised.
 */
export function FlowCanvas({
  clusterId,
  graph,
  selectedId,
  onSelect,
  paused,
}: {
  clusterId: string;
  graph: FlowGraphView;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  paused: boolean;
}) {
  const layout = useFlowLayout(graph);
  const wrapper = useRef<HTMLDivElement>(null);
  const reducedMotion = useReducedMotion();
  const [hovered, setHovered] = useState<string | null>(null);
  const [showText, setShowText] = useState(true);
  const [visible, setVisible] = useState(true);
  const [menu, setMenu] = useState<{ id: string; anchor: MenuAnchor; opener: HTMLElement } | null>(null);
  const openMenu = useCallback((id: string, anchor: MenuAnchor, opener: HTMLElement) => {
    setMenu({ id, anchor, opener });
  }, []);
  const menuNode = menu ? (graph.nodes ?? []).find((n) => n.id === menu.id) : undefined;

  useEffect(() => {
    let onScreen = true;
    const update = () => setVisible(onScreen && document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', update);
    let observer: IntersectionObserver | undefined;
    if (typeof IntersectionObserver !== 'undefined' && wrapper.current) {
      observer = new IntersectionObserver(([entry]) => {
        onScreen = entry.isIntersecting;
        update();
      });
      observer.observe(wrapper.current);
    }
    return () => {
      document.removeEventListener('visibilitychange', update);
      observer?.disconnect();
    };
  }, []);

  const motion: FlowCanvasState['motion'] = reducedMotion ? 'off' : paused || !visible ? 'paused' : 'running';

  const emphasisId = hovered ?? selectedId;
  const emphasis = useMemo(() => (emphasisId ? pathThrough(graph, emphasisId) : null), [graph, emphasisId]);
  const dots = useMemo(
    () =>
      allocateDots(
        (graph.edges ?? []).map((e) => ({
          id: e.id!,
          rate: e.rate,
          animatable: e.rateSource !== 'NONE' && !e.stale,
        })),
      ),
    [graph],
  );
  const model = useMemo(
    () => toReactFlow(graph, layout.positions, dots, emphasis),
    [graph, layout.positions, dots, emphasis],
  );

  // Pausing the SVG timeline freezes every dot where it is, which a reduced frame rate or a
  // removed element cannot do; it also costs nothing while paused.
  useEffect(() => {
    wrapper.current?.querySelectorAll('svg').forEach((svg) => {
      if (motion === 'running') svg.unpauseAnimations?.();
      else svg.pauseAnimations?.();
    });
  }, [motion, model]);

  const context = useMemo<FlowCanvasState>(
    () => ({ select: onSelect, openMenu, emphasize: setHovered, showText, motion }),
    [onSelect, openMenu, showText, motion],
  );
  const dense = model.nodes.length > DENSE_NODES;
  const laidOut = Object.keys(layout.positions).length > 0;

  return (
    <div>
      {layout.error ? (
        <Alert color="red" variant="light" title="The graph could not be laid out" mb="xs">
          {layout.error} The table still lists every shown path.
        </Alert>
      ) : null}
      <div
        ref={wrapper}
        className={classes.wrapper}
        onKeyDown={(event) => {
          if (event.key === 'Escape') onSelect(null);
        }}
      >
        {!laidOut ? (
          <div className={classes.overlay} aria-busy="true" aria-label="Laying out the graph">
            <Loader size="sm" />
          </div>
        ) : null}
        <FlowCanvasContext.Provider value={context}>
          <ReactFlowProvider>
            <ReactFlow
              nodes={model.nodes}
              edges={model.edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              minZoom={0.15}
              maxZoom={1.6}
              nodesDraggable={false}
              nodesConnectable={false}
              nodesFocusable={false}
              edgesFocusable={false}
              elementsSelectable={false}
              onlyRenderVisibleElements={dense}
              onPaneClick={() => onSelect(null)}
              // The menu is anchored to a point on screen; panning moves the node away from it.
              onMoveStart={() => setMenu(null)}
              proOptions={{ hideAttribution: true }}
            >
              <Background gap={24} />
              <Controls showInteractive={false} />
              <FlowMiniMap />
              <ZoomDetail onChange={setShowText} />
              <RefitOnLayout signature={layout.pending ? null : layoutSignature(graph)} />
              <RevealSelected id={selectedId} ready={laidOut && !layout.pending} />
            </ReactFlow>
          </ReactFlowProvider>
        </FlowCanvasContext.Provider>
      </div>
      <FlowLegend motion={motion} />
      <AnchoredMenu
        opened={menu !== null}
        anchor={menu?.anchor ?? null}
        label={`Actions for ${menuNode?.label ?? 'node'}`}
        onClose={() => {
          const opener = menu?.opener;
          setMenu(null);
          if (opener?.isConnected) opener.focus();
        }}
      >
        {menuNode && hasActions(menuNode) ? (
          <FlowNodeActions
            clusterId={clusterId}
            node={menuNode}
            restoreFocus={() => {
              if (menu?.opener.isConnected) menu.opener.focus();
            }}
          />
        ) : (
          <Text size="sm" c="dimmed" px="sm" py={6}>
            Nothing to open for this node.
          </Text>
        )}
      </AnchoredMenu>
    </div>
  );
}

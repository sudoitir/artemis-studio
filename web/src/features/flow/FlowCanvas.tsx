import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
} from '@xyflow/react';
import { Alert, Loader } from '@mantine/core';
import { useReducedMotion } from '@mantine/hooks';

import type { FlowGraphView } from './api.ts';
import { FlowCanvasContext, type FlowCanvasState } from './canvasContext.ts';
import { allocateDots } from './edgeEncoding.ts';
import { FlowEdge } from './FlowEdge.tsx';
import { DENSE_NODES, layoutSignature, pathThrough, toReactFlow } from './flowLayout.ts';
import { FlowLegend } from './FlowLegend.tsx';
import { AddressNode, ClientNode, LaneNode, QueueNode } from './FlowNodes.tsx';
import { useFlowLayout } from './useFlowLayout.ts';
import classes from './FlowCanvas.module.css';

const nodeTypes = { client: ClientNode, address: AddressNode, queue: QueueNode, lane: LaneNode };
const edgeTypes = { flow: FlowEdge };

/** Labels and dots below this zoom would be unreadable specks; widths still carry the rate. */
const TEXT_ZOOM = 0.55;

function ZoomDetail({ onChange }: { onChange: (showText: boolean) => void }) {
  const showText = useStore((s) => s.transform[2] >= TEXT_ZOOM);
  useEffect(() => onChange(showText), [showText, onChange]);
  return null;
}

/** Fit once per layout: a new set of nodes gets a fresh view, a rate-only refresh keeps the operator's. */
function RefitOnLayout({ signature }: { signature: string | null }) {
  const flow = useReactFlow();
  useEffect(() => {
    if (!signature) return;
    const frame = requestAnimationFrame(() => {
      void flow.fitView({ padding: 0.12, maxZoom: 1 });
    });
    return () => cancelAnimationFrame(frame);
  }, [flow, signature]);
  return null;
}

/**
 * The flow graph (flow-visualization spec, ADR-0080): four columns laid out by ELK in a worker,
 * rates as width, labels and moving dots, faults in words, and the path through whatever is hovered,
 * focused or selected emphasised.
 */
export function FlowCanvas({
  graph,
  selectedId,
  onSelect,
  paused,
}: {
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
    () => ({ select: onSelect, emphasize: setHovered, showText, motion }),
    [onSelect, showText, motion],
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
              fitView
              fitViewOptions={{ padding: 0.12, maxZoom: 1 }}
              minZoom={0.15}
              maxZoom={1.6}
              nodesDraggable={false}
              nodesConnectable={false}
              nodesFocusable={false}
              edgesFocusable={false}
              elementsSelectable={false}
              onlyRenderVisibleElements={dense}
              onPaneClick={() => onSelect(null)}
              proOptions={{ hideAttribution: true }}
            >
              <Background gap={24} />
              <Controls showInteractive={false} />
              {dense ? <MiniMap pannable zoomable nodeClassName={classes.minimapNode} /> : null}
              <ZoomDetail onChange={setShowText} />
              <RefitOnLayout signature={layout.pending ? null : layoutSignature(graph)} />
            </ReactFlow>
          </ReactFlowProvider>
        </FlowCanvasContext.Provider>
      </div>
      <FlowLegend motion={motion} />
    </div>
  );
}

import { memo, useContext, useEffect, useRef } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';

import { RoutingCanvasContext } from './canvasContext.ts';
import { KIND_WORDS, nodeSentence, STATE_SHORT } from './routingGraph.ts';
import type { RoutingNodeData } from './routingLayout.ts';
import classes from './RoutingCanvas.module.css';

/**
 * One element of the routing canvas.
 *
 * <p>Its kind is carried by its shape and its state by its words; colour appears
 * only on an element that is not what was declared, or is declared and not doing
 * it. Removing colour removes no information (frontend rule: presentation).
 *
 * <p>It is a real focusable button with a sentence for a name, because React Flow
 * nodes are not reachable from the keyboard on their own (ADR-0090 D10). The
 * canvas owns the single tab stop and moves it with the arrow keys; this element
 * only reports where it is and when it was focused directly.
 */
function RoutingNode({
  id,
  data,
  inbound,
  outbound,
}: {
  id: string;
  data: RoutingNodeData;
  inbound: boolean;
  outbound: boolean;
}) {
  const { focusedId, register, focus, select } = useContext(RoutingCanvasContext);
  const ref = useRef<HTMLButtonElement>(null);
  const view = data.view;
  // Not what was declared, or declared and not doing it.
  const attention = view.state === 'DECLARED_ONLY' || view.state === 'OBSERVED_ONLY' || view.fault !== null;

  useEffect(() => {
    register(id, ref.current);
    return () => register(id, null);
  }, [id, register]);

  return (
    <>
      {inbound ? <Handle type="target" position={Position.Left} className={classes.handle} /> : null}
      <button
        ref={ref}
        type="button"
        className={classes.node}
        data-kind={view.kind}
        data-attention={attention || undefined}
        data-selected={data.selected || undefined}
        tabIndex={focusedId === id ? 0 : -1}
        aria-label={nodeSentence(view)}
        onFocus={() => focus(id)}
        onClick={() => select(id)}
      >
        <span className={classes.kind} aria-hidden="true">
          {KIND_WORDS[view.kind]}
        </span>
        <span className={classes.name} aria-hidden="true" title={view.name}>
          {view.name}
        </span>
        <span className={classes.state} data-attention={attention || undefined} aria-hidden="true">
          {view.fault ?? STATE_SHORT[view.state]}
        </span>
      </button>
      {outbound ? <Handle type="source" position={Position.Right} className={classes.handle} /> : null}
    </>
  );
}

export const AddressNode = memo(function AddressNode({ id, data }: NodeProps) {
  return <RoutingNode id={id} data={data as RoutingNodeData} inbound outbound />;
});

export const QueueNode = memo(function QueueNode({ id, data }: NodeProps) {
  return <RoutingNode id={id} data={data as RoutingNodeData} inbound outbound />;
});

export const DivertNode = memo(function DivertNode({ id, data }: NodeProps) {
  return <RoutingNode id={id} data={data as RoutingNodeData} inbound outbound />;
});

export const BridgeNode = memo(function BridgeNode({ id, data }: NodeProps) {
  return <RoutingNode id={id} data={data as RoutingNodeData} inbound outbound />;
});

/** A target is on another broker: nothing on this canvas is downstream of it. */
export const TargetNode = memo(function TargetNode({ id, data }: NodeProps) {
  return <RoutingNode id={id} data={data as RoutingNodeData} inbound outbound={false} />;
});

import { memo, useContext, useEffect, useRef } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import {
  IconArrowFork,
  IconBuildingBridge2,
  IconInbox,
  IconServer2,
  IconStack2,
  type Icon,
} from '@tabler/icons-react';

import { RoutingCanvasContext } from './canvasContext.ts';
import { KIND_WORDS, nodeSentence, STATE_SHORT, type RoutingKind } from './routingGraph.ts';
import type { RoutingNodeData } from './routingLayout.ts';
import classes from './RoutingCanvas.module.css';

const GLYPH: Record<RoutingKind, Icon> = {
  address: IconInbox,
  queue: IconStack2,
  divert: IconArrowFork,
  bridge: IconBuildingBridge2,
  target: IconServer2,
};

/** The kind, short enough for a card; the full word is in the accessible name. */
const KIND_SHORT: Record<RoutingKind, string> = { ...KIND_WORDS, target: 'Other broker' };

/**
 * One element of the routing canvas: a raised card with a kind glyph and word, the name, and
 * its state as a short chip.
 *
 * <p>Its kind is carried by the glyph and the word, and its state by words; colour appears only
 * on an element that is not what was declared, or is declared and not doing it. Removing colour
 * removes no information (frontend rule: presentation). A target on another broker keeps a
 * dashed outline, because it is the one element this canvas cannot read.
 *
 * <p>It is a real focusable button with a sentence for a name, because React Flow nodes are not
 * reachable from the keyboard on their own (ADR-0090 D10). The canvas owns the single tab stop
 * and moves it with the arrow keys; this element only reports where it is and when it was
 * focused directly. Connection handles show on hover or focus, and only while the operator may
 * write — a handle that cannot be used is a promise the canvas does not keep.
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
  const { focusedId, canWrite, register, focus, select } = useContext(RoutingCanvasContext);
  const ref = useRef<HTMLButtonElement>(null);
  const view = data.view;
  // Not what was declared, or declared and not doing it.
  const attention = view.state === 'DECLARED_ONLY' || view.state === 'OBSERVED_ONLY' || view.fault !== null;
  const Glyph = GLYPH[view.kind];

  useEffect(() => {
    register(id, ref.current);
    return () => register(id, null);
  }, [id, register]);

  return (
    <>
      {/* Lines attach to handles, so they are always there; they are shown only where they can be used. */}
      {inbound ? (
        <Handle type="target" position={Position.Left} className={classes.handle} data-usable={canWrite || undefined} />
      ) : null}
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
          <Glyph size={14} stroke={1.75} className={classes.glyph} />
          {KIND_SHORT[view.kind]}
        </span>
        <span className={classes.name} aria-hidden="true" title={view.name}>
          {view.name}
        </span>
        <span className={classes.state} data-attention={attention || undefined} aria-hidden="true">
          {view.fault ?? STATE_SHORT[view.state]}
        </span>
      </button>
      {outbound ? (
        <Handle type="source" position={Position.Right} className={classes.handle} data-usable={canWrite || undefined} />
      ) : null}
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

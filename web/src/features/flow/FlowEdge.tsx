import { memo, useContext } from 'react';
import { EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';

import { FlowCanvasContext } from './canvasContext.ts';
import { crossingSeconds, speedBucket, STROKE_WIDTH, widthTier } from './edgeEncoding.ts';
import { edgeText } from './flowFormat.ts';
import type { FlowEdgeData } from './flowLayout.ts';
import classes from './FlowCanvas.module.css';

/**
 * Dots travelling the edge. Memoised on primitives only, so a refresh whose rate stays in the same
 * speed bucket re-renders nothing here and the running SMIL animation is never restarted.
 */
export const FlowDots = memo(function FlowDots({
  path,
  seconds,
  count,
}: {
  path: string;
  seconds: number;
  count: number;
}) {
  return (
    <g aria-hidden="true">
      {Array.from({ length: count }, (_, i) => (
        <circle key={i} r={2.6} className={classes.dot}>
          <animateMotion
            dur={`${seconds}s`}
            repeatCount="indefinite"
            // Negative begin spreads the dots along the path instead of stacking them at its start.
            begin={`${(-seconds * i) / count}s`}
            path={path}
          />
        </circle>
      ))}
    </g>
  );
});

export const FlowEdge = memo(function FlowEdge({
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
}: EdgeProps) {
  const d = data as FlowEdgeData;
  const view = d.view;
  const { showText, motion } = useContext(FlowCanvasContext);
  const [path, labelX, labelY] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });
  const tier = widthTier(view.rate);
  const fault = (view.faults?.length ?? 0) > 0;

  return (
    <>
      <path
        d={path}
        fill="none"
        className={classes.edge}
        data-tier={tier}
        data-kind={view.kind}
        data-fault={fault || undefined}
        data-stale={view.stale || undefined}
        data-dimmed={d.dimmed || undefined}
        style={{ strokeWidth: view.kind === 'BRIDGE' || view.kind === 'CLUSTER_HOP' ? STROKE_WIDTH[tier] + 2 : STROKE_WIDTH[tier] }}
      />
      {view.kind === 'BRIDGE' || view.kind === 'CLUSTER_HOP' ? (
        // A second, narrower stroke in the canvas colour turns one line into two: forwarding
        // between brokers reads differently from routing inside one.
        <path d={path} fill="none" className={classes.edgeInner} data-dimmed={d.dimmed || undefined} />
      ) : null}
      {showText && motion !== 'off' && d.dots > 0 && !d.dimmed ? (
        <FlowDots path={path} seconds={crossingSeconds(speedBucket(view.rate))} count={d.dots} />
      ) : null}
      {showText ? (
        <EdgeLabelRenderer>
          <div
            className={`${classes.edgeLabel} nopan nodrag`}
            data-fault={fault || undefined}
            data-dimmed={d.dimmed || undefined}
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          >
            {edgeText(view)}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  );
});

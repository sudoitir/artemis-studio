import { memo } from 'react';
import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react';

import type { RoutingEdgeData } from './routingLayout.ts';
import classes from './RoutingCanvas.module.css';

/**
 * A line between two elements. One weight, one colour: on this canvas a line carries no
 * measurement, only "this feeds that", so varying either would encode something that is not
 * there (ADR-0090 D5). Selecting an element brings its own lines forward and lets the rest
 * recede — emphasis, not data.
 *
 * <p>A divert's outgoing line carries its effect as a word on the line, "copies" or "takes",
 * because that is the most consequential fact about it. The word repeats what the divert's and
 * the line's accessible names already say, so it is hidden from assistive technology.
 */
export const RoutingEdge = memo(function RoutingEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  data,
}: EdgeProps) {
  const { view, emphasis } = data as RoutingEdgeData;
  const [path, labelX, labelY] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });
  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        className={classes.edge}
        data-emphasis={emphasis}
        markerEnd={markerEnd}
        aria-label={view.label}
        // Lines are not interactive here; an invisible hit area would only steal pointer events.
        interactionWidth={0}
      />
      {view.chip ? (
        <EdgeLabelRenderer>
          <div
            className={`${classes.edgeChip} nodrag nopan`}
            data-emphasis={emphasis}
            aria-hidden="true"
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          >
            {view.chip}
          </div>
        </EdgeLabelRenderer>
      ) : null}
    </>
  );
});

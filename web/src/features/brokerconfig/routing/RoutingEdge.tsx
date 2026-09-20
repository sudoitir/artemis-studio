import { memo } from 'react';
import { getBezierPath, type EdgeProps } from '@xyflow/react';

import type { RoutingEdgeData } from './routingLayout.ts';
import classes from './RoutingCanvas.module.css';

/**
 * A line between two elements. One weight, one colour: on this canvas a line
 * carries no measurement, only "this feeds that", so varying either would encode
 * something that is not there. What it means is in its own accessible name and in
 * both of the elements it joins, never in the line alone.
 */
export const RoutingEdge = memo(function RoutingEdge({
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  data,
}: EdgeProps) {
  const [path] = getBezierPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });
  return <path d={path} className={classes.edge} markerEnd={markerEnd} aria-label={(data as RoutingEdgeData).view.label} />;
});

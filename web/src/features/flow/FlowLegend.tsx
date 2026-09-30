import type { CSSProperties, ReactNode } from 'react';

import { RATE_CAP, widthPx } from './edgeEncoding.ts';
import classes from './FlowCanvas.module.css';

/**
 * Reference weights. 1, 100 and the cap rather than 50: on the square-root scale 1 and 50 msg/s
 * are only 1.5px apart, under the ~1.8px a reader can tell apart at a glance; 100 is 2.3px clear.
 */
const REFERENCE_RATES = [
  { rate: 1, label: '1 msg/s' },
  { rate: 100, label: '100 msg/s' },
  { rate: RATE_CAP, label: `${RATE_CAP}+ msg/s` },
];

/** One legend entry: a mark the stylesheet draws, then what it means. */
function LegendItem({ mark, children }: Readonly<{ mark?: ReactNode; children: ReactNode }>) {
  return (
    <span className={classes.legendItem}>
      {mark}
      {children}
    </span>
  );
}

const shape = (name: string) => <span className={classes.legendShape} data-shape={name} aria-hidden="true" />;

const line = (attribute: Record<string, string>, style?: CSSProperties) => (
  <span className={classes.legendLine} {...attribute} style={style} aria-hidden="true" />
);

/** Every mark and line the canvas can draw, docked under it rather than floating over nodes. */
export function FlowLegend({ motion }: Readonly<{ motion: 'running' | 'paused' | 'off' }>) {
  return (
    <div className={classes.legend} aria-label="Legend">
      <LegendItem mark={shape('pill')}>client</LegendItem>
      <LegendItem mark={shape('tag')}>address</LegendItem>
      <LegendItem mark={shape('box')}>queue, bar = backlog</LegendItem>
      <LegendItem mark={shape('hex')}>other node or broker</LegendItem>
      {REFERENCE_RATES.map(({ rate, label }) => (
        <LegendItem
          key={rate}
          mark={line({ 'data-line': 'flowing' }, { '--edge-width': `${widthPx(rate)}px` } as CSSProperties)}
        >
          {label}
        </LegendItem>
      ))}
      <LegendItem mark={line({ 'data-line': 'idle' })}>idle (thinnest, dashed)</LegendItem>
      <LegendItem mark={line({ 'data-line': 'unknown' })}>measuring (thinnest, dotted)</LegendItem>
      <LegendItem mark={line({ 'data-kind': 'DIVERT' })}>divert (not counted by the broker)</LegendItem>
      <LegendItem mark={line({ 'data-kind': 'BRIDGE' })}>bridge</LegendItem>
      <LegendItem mark={line({ 'data-kind': 'CLUSTER_HOP' })}>cluster redistribution</LegendItem>
      <LegendItem mark={line({ 'data-kind': 'DEAD_LETTER' })}>dead letter or expiry</LegendItem>
      <LegendItem mark={line({ 'data-fault': 'true' })}>fault, named in words</LegendItem>
      <LegendItem>
        Line width and dot speed both follow throughput (square-root scale, capped at {RATE_CAP} msg/s).
      </LegendItem>
      <LegendItem>
        {motion === 'off'
          ? 'Motion is off (reduced motion); width and labels carry the rate.'
          : 'Dots move left to right; faster and denser means busier.'}
      </LegendItem>
    </div>
  );
}

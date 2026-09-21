import type { CSSProperties } from 'react';

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

/** Every mark and line the canvas can draw, docked under it rather than floating over nodes. */
export function FlowLegend({ motion }: { motion: 'running' | 'paused' | 'off' }) {
  return (
    <div className={classes.legend} aria-label="Legend">
      <span className={classes.legendItem}>
        <span className={classes.legendShape} data-shape="pill" aria-hidden="true" />
        client
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendShape} data-shape="tag" aria-hidden="true" />
        address
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendShape} data-shape="box" aria-hidden="true" />
        queue, bar = backlog
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendShape} data-shape="hex" aria-hidden="true" />
        other node or broker
      </span>
      {REFERENCE_RATES.map(({ rate, label }) => (
        <span key={rate} className={classes.legendItem}>
          <span
            className={classes.legendLine}
            data-line="flowing"
            style={{ '--edge-width': `${widthPx(rate)}px` } as CSSProperties}
            aria-hidden="true"
          />
          {label}
        </span>
      ))}
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-line="idle" aria-hidden="true" />
        idle (thinnest, dashed)
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-line="unknown" aria-hidden="true" />
        measuring (thinnest, dotted)
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-kind="DIVERT" aria-hidden="true" />
        divert (not counted by the broker)
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-kind="BRIDGE" aria-hidden="true" />
        bridge
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-kind="CLUSTER_HOP" aria-hidden="true" />
        cluster redistribution
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-kind="DEAD_LETTER" aria-hidden="true" />
        dead letter or expiry
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-fault="true" aria-hidden="true" />
        fault, named in words
      </span>
      <span className={classes.legendItem}>
        Line width and dot speed both follow throughput (square-root scale, capped at {RATE_CAP} msg/s).
      </span>
      <span className={classes.legendItem}>
        {motion === 'off'
          ? 'Motion is off (reduced motion); width and labels carry the rate.'
          : 'Dots move left to right; faster and denser means busier.'}
      </span>
    </div>
  );
}

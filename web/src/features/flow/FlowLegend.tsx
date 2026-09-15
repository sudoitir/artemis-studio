import classes from './FlowCanvas.module.css';

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
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-tier="busy" aria-hidden="true" />
        busy (50+ msg/s)
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-tier="light" aria-hidden="true" />
        flowing
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-tier="idle" aria-hidden="true" />
        idle
      </span>
      <span className={classes.legendItem}>
        <span className={classes.legendLine} data-tier="unknown" aria-hidden="true" />
        measuring
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
        {motion === 'off'
          ? 'Motion is off (reduced motion); width and labels carry the rate.'
          : 'Dots move left to right; faster and denser means busier.'}
      </span>
    </div>
  );
}

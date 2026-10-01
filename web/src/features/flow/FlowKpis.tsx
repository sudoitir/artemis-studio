import { Stat } from '../../ui/Stat.tsx';
import type { FlowKpis as Kpis } from './api.ts';
import { formatCount, formatRate } from './flowFormat.ts';
import classes from './FlowView.module.css';

const MEASURING = 'Still measuring: a rate needs two samples.';

/** A total rate as a figure, or null while it is not known: "Unavailable" with its reason, never 0. */
const rate = (n: number | null | undefined) => (n === null || n === undefined ? null : formatRate(n));

/**
 * The cluster's current totals, leading the view (metrics spec: a view leads with the current
 * values). The caller names the section; each figure is a `Stat`, so an unknown total is stated.
 */
export function FlowKpis({ kpis }: Readonly<{ kpis: Kpis }>) {
  const faults = kpis.faults ?? 0;
  return (
    <div className={classes.kpis}>
      <Stat label="Messages in" value={rate(kpis.inRate)} unit="msg/s" unavailableReason={MEASURING} />
      <Stat label="Messages out" value={rate(kpis.outRate)} unit="msg/s" unavailableReason={MEASURING} />
      <Stat label="Backlog" value={formatCount(kpis.backlog ?? 0)} unit="waiting" />
      <Stat label="Clients" value={formatCount(kpis.clients ?? 0)} unit="connected" />
      {/* Colour only when something is wrong; the words carry the meaning. */}
      <Stat
        label="Faults"
        value={
          faults === 0 ? (
            'none'
          ) : (
            <span className={classes.alarm}>{`${faults} ${faults === 1 ? 'fault' : 'faults'}`}</span>
          )
        }
      />
    </div>
  );
}

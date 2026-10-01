import { Text } from '@mantine/core';

import { Notice } from '../../ui/Notice.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { SqlPlanView } from './api.ts';
import type { CostVerdict } from './costVerdict.ts';
import { noticeWords } from './notices.ts';
import classes from './ExplainStrip.module.css';

/** A labelled run of predicates, or nothing when there are none. */
function Predicates({ label, items }: Readonly<{ label: string; items: string[] }>) {
  if (items.length === 0) return null;
  return (
    <span>
      {label}:{' '}
      {items.map((p) => (
        <span key={p} className={classes.predicate}>
          {p}{' '}
        </span>
      ))}
    </span>
  );
}

/** Whether the verdict was worked out from a plan, so that the plan's own details apply to it. */
const FROM_PLAN: ReadonlySet<CostVerdict['badge']> = new Set(['No cost', 'Index', 'Scan', 'Broker-filtered']);

/**
 * What the query will cost, stated before it runs and worked out without contacting a broker: one
 * sentence of words and numbers under the editor, then what it rests on (the predicates the broker
 * filters and the ones Studio scans) and what the plan cannot promise.
 *
 * <p>The sentence is not a live region: it changes as the operator types, and announcing every
 * estimate would bury the editor. It describes the editor and Run instead (`id`, which they point
 * at with `aria-describedby`). The estimate is always stated; an absent number reads as zero, and zero
 * is the most dangerous possible misreading of "how much will this examine".
 */
export function ExplainStrip({
  id,
  verdict,
  plan,
}: Readonly<{
  /** What the editor and Run are described by. */
  id: string;
  verdict: CostVerdict;
  plan?: SqlPlanView;
}>) {
  const details = FROM_PLAN.has(verdict.badge) ? plan : undefined;
  const pushedDown = details?.pushedDown ?? [];
  const scanned = details?.scanned ?? [];
  const notices = (details?.notices ?? []).map(noticeWords);

  return (
    <div className={classes.cost}>
      <div id={id} className={classes.verdict}>
        <StatusBadge tone={verdict.tone}>{verdict.badge}</StatusBadge>
        <Text size="sm" component="span">
          {verdict.sentence}
        </Text>
      </div>
      {pushedDown.length > 0 || scanned.length > 0 ? (
        <div className={classes.predicates}>
          <Predicates label="Pushed down" items={pushedDown} />
          <Predicates label="Scanned by Studio" items={scanned} />
        </div>
      ) : null}
      {notices.length > 0 ? (
        <Notice title="About this plan" tone={notices.some((n) => n.tone) ? 'warning' : 'neutral'}>
          <ul className={classes.statements}>
            {notices.map((notice) => (
              <li key={notice.text}>{notice.text}</li>
            ))}
          </ul>
        </Notice>
      ) : null}
    </div>
  );
}

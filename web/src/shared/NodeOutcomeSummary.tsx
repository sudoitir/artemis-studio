import { Text } from '@mantine/core';

import type { LifecycleOutcomeView, NodeOutcomeView } from '../api/client.ts';
import classes from './NodeOutcomeSummary.module.css';

/**
 * One cluster-wide lifecycle result, rendered as a single object (ADR-0049 D2).
 *
 * <p>Used for both the dry-run preview and the result, so what the operator
 * confirmed and what actually happened are visually comparable — a preview that
 * looks nothing like its outcome makes the two impossible to check against each
 * other.
 *
 * <p>Every node's state is carried in words. Colour is redundant with the text
 * and appears only where something is wrong, so a fully applied command reads as
 * near-monochrome and the eye stops at the one row that did not.
 */
export function NodeOutcomeSummary({
  outcome,
  destructive = false,
  alreadyLabel,
  countNoun = 'message',
  verbFuture = 'would destroy',
  verbPast = 'destroyed',
}: {
  outcome: LifecycleOutcomeView;
  /** A destroy shows the message counts; a pause has none worth a column. */
  destructive?: boolean;
  /**
   * What `ALREADY` means for this command. It defaults to the lifecycle wording,
   * but a connection close needs "already gone" — "already in this state" reads
   * as though the operator had asked for something else.
   */
  alreadyLabel?: string;
  /**
   * What the counts are counting, and the verb for them. Defaults to the queue
   * delete's wording; a connection close affects consumers, not messages, and
   * saying "destroyed" there would overstate what happened.
   */
  countNoun?: string;
  verbFuture?: string;
  verbPast?: string;
}) {
  const verdict = verdictFor(outcome);

  return (
    <div className={classes.summary}>
      <div className={classes.headline}>
        <Text size="sm" className={classes.verdict} data-tone={verdict.tone}>
          {verdict.text}
        </Text>
        {destructive ? (
          <Text size="xs" className={classes.total}>
            {outcome.dryRun ? `${verbFuture} ` : `${verbPast} `}
            {outcome.totalAffected.toLocaleString()} {countNoun}
            {outcome.totalAffected === 1 ? '' : 's'}
          </Text>
        ) : null}
      </div>

      <div className={classes.rows}>
        {outcome.nodes.map((node) => (
          <NodeRow
            key={node.nodeId}
            node={node}
            destructive={destructive}
            alreadyLabel={alreadyLabel}
          />
        ))}
      </div>
    </div>
  );
}

function NodeRow({
  node,
  destructive,
  alreadyLabel,
}: {
  node: NodeOutcomeView;
  destructive: boolean;
  alreadyLabel?: string;
}) {
  const { text, tone } = statusWords(node.status, alreadyLabel);
  return (
    <>
      <div className={classes.row}>
        <Text size="sm" className={classes.node}>
          {node.nodeName}
        </Text>
        <div className={classes.state}>
          {destructive && node.affected != null ? (
            <Text size="xs" className={classes.count}>
              {node.affected.toLocaleString()}
            </Text>
          ) : null}
          <Text size="xs" className={classes.status} data-tone={tone}>
            {text}
          </Text>
        </div>
      </div>
      {node.error ? (
        <Text size="xs" className={classes.error}>
          {node.error}
        </Text>
      ) : null}
    </>
  );
}

/**
 * The one line that tells an operator the shape of the result. Stated before the
 * rows so a partial application is apparent without reading any of them.
 */
function verdictFor(outcome: LifecycleOutcomeView): { text: string; tone?: 'warning' | 'danger' } {
  const targets = outcome.nodes.filter((n) => n.status !== 'SKIPPED_NOT_LIVE').length;
  const skipped = outcome.nodes.length - targets;

  if (outcome.dryRun) {
    const suffix = skipped > 0 ? `, ${skipped} not live and will be skipped` : '';
    return { text: `Would apply to ${targets} of ${outcome.nodes.length} nodes${suffix}` };
  }
  if (outcome.partial) {
    return { text: 'Applied to some nodes and not others', tone: 'warning' };
  }
  const failed = outcome.nodes.filter((n) => n.status === 'FAILED').length;
  if (failed > 0 && failed === targets) {
    return { text: 'Failed on every node', tone: 'danger' };
  }
  if (skipped > 0) {
    return { text: `Applied to all ${targets} live nodes · ${skipped} not live`, tone: 'warning' };
  }
  return { text: `Applied to all ${targets} nodes` };
}

/**
 * A node's state as words. `ALREADY` is deliberately worded as a success — it
 * means the node is in the requested state, which is what was asked for.
 */
function statusWords(
  status: NodeOutcomeView['status'],
  alreadyLabel?: string,
): {
  text: string;
  tone?: 'warning' | 'danger';
} {
  switch (status) {
    case 'WOULD_APPLY':
      return { text: 'would apply' };
    case 'APPLIED':
      return { text: 'applied' };
    case 'ALREADY':
      return { text: alreadyLabel ?? 'already in this state' };
    case 'SKIPPED_NOT_LIVE':
      return { text: 'skipped — not live', tone: 'warning' };
    case 'FAILED':
      return { text: 'failed', tone: 'danger' };
    default:
      return { text: status };
  }
}

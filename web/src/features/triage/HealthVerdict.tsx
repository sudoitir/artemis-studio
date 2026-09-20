import { Text } from '@mantine/core';

import type { ConsumerHealthView } from './api.ts';
import { isUnmeasured, verdictCopy } from './verdict.ts';
import styles from './HealthVerdict.module.css';

const TONE_CLASS = {
  danger: styles.danger,
  warning: styles.warning,
} as const;

/**
 * One queue's verdict, as a word.
 *
 * Colour never carries the verdict alone: the same text is present in every
 * scheme and at every contrast, and a healthy row carries no colour at all.
 */
export function HealthVerdict({ row }: { row: ConsumerHealthView }) {
  const copy = verdictCopy(row.verdict);
  const unmeasured = isUnmeasured(row);
  const className = unmeasured
    ? styles.unmeasured
    : copy.tone
      ? TONE_CLASS[copy.tone]
      : undefined;

  return (
    <span className={styles.verdict}>
      <Text span size="sm" className={className}>
        {copy.label}
      </Text>
      {row.stale ? (
        <Text span size="xs" className={styles.stale}>
          {' '}
          · stale
        </Text>
      ) : null}
    </span>
  );
}

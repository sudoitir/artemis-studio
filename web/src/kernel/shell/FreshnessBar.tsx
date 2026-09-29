import { useEffect, useRef, useState } from 'react';
import { ActionIcon, Group, Text, Tooltip } from '@mantine/core';
import { IconPlayerPause, IconPlayerPlay } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import styles from './FreshnessBar.module.css';

import { refreshActiveQueries, setPollingPaused, usePendingChange, usePollingPaused } from '../api/polling.ts';
import { useStreamStatus } from '../stream/useClusterStream.ts';
import { absoluteLabel, elapsedLabel, toServerMs, useServerNow } from '../time/time.ts';
import { useDisplayZone } from '../time/timezone.ts';
import { useFreshness } from './useFreshness.ts';

export type FreshnessState = 'live' | 'polling' | 'reconnecting' | 'offline' | 'paused';

const LABELS: Record<FreshnessState, string> = {
  live: 'Live',
  polling: 'Polling',
  reconnecting: 'Reconnecting…',
  offline: 'Offline',
  paused: 'Paused',
};

/**
 * The one place that answers "is this current?" — in the header, on every route
 * (ADR-0052).
 *
 * Stream health and data health are separate states. A cluster whose stream is
 * down but whose queries are succeeding is `Polling`, not `Offline`; conflating
 * them would cry wolf on every proxy hiccup. A route that mounts no stream is
 * `Polling` too, which is the literal truth there.
 */
export function FreshnessBar() {
  const qc = useQueryClient();
  const { lastUpdatedAt, hasError, observed } = useFreshness();
  const stream = useStreamStatus();
  const paused = usePollingPaused();
  const pending = usePendingChange();
  const now = useServerNow();
  // The tooltip below is an absolute timestamp, so this view follows the zone.
  useDisplayZone();

  // Resuming refetches the screen: the intervals pick up again on their own
  // (ADR-0118), but the first thing an operator needs after unpausing is current
  // data, not data up to one interval old.
  const wasPaused = useRef(paused);
  useEffect(() => {
    const resumed = wasPaused.current && !paused;
    wasPaused.current = paused;
    if (resumed) void refreshActiveQueries(qc);
  }, [paused, qc]);

  const state: FreshnessState = paused
    ? 'paused'
    : hasError
      ? 'offline'
      : stream === 'live'
        ? 'live'
        : stream === 'reconnecting' || stream === 'connecting'
          ? 'reconnecting'
          : stream === 'offline'
            ? 'offline'
            : 'polling';

  const label = LABELS[state];
  // `dataUpdatedAt` is TanStack's own `Date.now()`. Normalised onto Studio's
  // timeline here, at the one boundary it enters, so the subtraction below is
  // not a comparison between two different clocks (`time.ts`).
  const updated = lastUpdatedAt === null ? null : new Date(toServerMs(lastUpdatedAt));

  return (
    <Group gap="xs" wrap="nowrap" className={styles.bar}>
      <StateAnnouncement state={state} />
      <span className={styles.dot} data-state={state} aria-hidden="true" />
      <Text size="xs" c="dimmed" className={styles.label}>
        {label}
        {state === 'paused' && pending ? ' · new data available' : null}
      </Text>
      {updated && observed > 0 ? (
        <Text size="xs" c="dimmed" className={`${styles.label} ${styles.elapsed}`}>
          ·{' '}
          <time dateTime={updated.toISOString()} title={absoluteLabel(updated.getTime())}>
            updated {elapsedLabel(now - updated.getTime())} ago
          </time>
        </Text>
      ) : null}
      {/* Paused is carried three ways, none of them colour: the pressed fill
          here, `aria-pressed`, and the word in the label beside it. A healthy
          screen stays near-monochrome, and paused is not an error. */}
      <Tooltip label={paused ? 'Resume auto-refresh' : 'Pause auto-refresh'} withArrow>
        <ActionIcon
          variant="subtle"
          color="gray"
          className={paused ? styles.pressed : undefined}
          aria-label={paused ? 'Resume auto-refresh' : 'Pause auto-refresh'}
          aria-pressed={paused}
          onClick={() => setPollingPaused(!paused)}
        >
          {paused ? <IconPlayerPlay size={18} aria-hidden /> : <IconPlayerPause size={18} aria-hidden />}
        </ActionIcon>
      </Tooltip>
    </Group>
  );
}

/**
 * Announce transitions, never ticks.
 *
 * The elapsed label changes every second; putting it inside the live region would
 * narrate over whatever the operator is actually doing. Only the state word goes
 * in, and only when it changes.
 */
function StateAnnouncement({ state }: { state: FreshnessState }) {
  const [message, setMessage] = useState('');
  const previous = useRef(state);

  useEffect(() => {
    if (previous.current === state) return;
    previous.current = state;
    setMessage(`Data ${LABELS[state].toLowerCase().replace('…', '')}`);
  }, [state]);

  return (
    <span className={styles.announcement} aria-live="polite" role="status">
      {message}
    </span>
  );
}

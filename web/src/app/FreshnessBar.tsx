import { useEffect, useRef, useState } from 'react';
import { ActionIcon, Group, Text, Tooltip } from '@mantine/core';
import { IconPlayerPause, IconPlayerPlay, IconRefresh } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import styles from './FreshnessBar.module.css';

import {
  refreshActiveQueries,
  setPollingPaused,
  usePendingChange,
  usePollingPaused,
} from '../api/polling.ts';
import { useStreamStatus } from '../api/stream.ts';
import { elapsedLabel, useFreshness, useNow } from './useFreshness.ts';

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
  const { lastUpdatedAt, isFetching, hasError, observed } = useFreshness();
  const stream = useStreamStatus();
  const paused = usePollingPaused();
  const pending = usePendingChange();
  const now = useNow();

  // Resuming must actually restart the intervals. TanStack re-reads
  // `refetchInterval` when a query re-renders, not when a module-level flag
  // flips, so a resume that only cleared the flag would leave every screen
  // waiting for an unrelated change. Refetching on resume is also what an
  // operator wants: the first thing they need after unpausing is current data.
  const wasPaused = useRef(paused);
  useEffect(() => {
    const resumed = wasPaused.current && !paused;
    wasPaused.current = paused;
    if (resumed) refreshActiveQueries(qc);
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
  const updated = lastUpdatedAt === null ? null : new Date(lastUpdatedAt);

  return (
    <Group gap="xs" wrap="nowrap" className={styles.bar}>
      <StateAnnouncement state={state} />
      <span className={styles.dot} data-state={state} aria-hidden="true" />
      <Text size="xs" c="dimmed" className={styles.label}>
        {label}
        {state === 'paused' && pending ? ' · new data available' : null}
      </Text>
      {updated && observed > 0 ? (
        <Text size="xs" c="dimmed" className={styles.label}>
          ·{' '}
          <time dateTime={updated.toISOString()} title={updated.toLocaleString()}>
            updated {elapsedLabel(now - updated.getTime())} ago
          </time>
        </Text>
      ) : null}
      <Tooltip label="Refresh data" withArrow>
        <ActionIcon
          variant="subtle"
          size="sm"
          aria-label="Refresh data"
          loading={isFetching}
          onClick={() => refreshActiveQueries(qc)}
        >
          <IconRefresh size={16} />
        </ActionIcon>
      </Tooltip>
      <Tooltip label={paused ? 'Resume auto-refresh' : 'Pause auto-refresh'} withArrow>
        <ActionIcon
          variant="subtle"
          size="sm"
          aria-label={paused ? 'Resume auto-refresh' : 'Pause auto-refresh'}
          aria-pressed={paused}
          onClick={() => setPollingPaused(!paused)}
        >
          {paused ? <IconPlayerPlay size={16} /> : <IconPlayerPause size={16} />}
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

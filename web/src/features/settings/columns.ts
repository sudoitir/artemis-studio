import { createElement } from 'react';

import type { Column } from '../../ui/table/index.ts';
import type { JobHealth, NodeHealth, ReplicaHealth } from './api.ts';
import {
  Figure,
  Lag,
  LastFailure,
  Moment,
  NodeName,
  ReplicaHealthCell,
  ReplicaName,
  ReplicaState,
  Verdict,
} from './cells.tsx';
import { JOB_STATES, REPLICA_STATES, figureText, heartbeatText, lagText, momentText } from './healthWords.ts';

const round = (ms: number | null | undefined) => (ms == null ? null : Math.round(ms));

/**
 * The background jobs' columns. The job and its health identify and judge a job and are never hidden;
 * the module is the first to go when the table is narrow.
 */
export function jobColumns(now: number): Column<JobHealth>[] {
  return [
    { id: 'job', header: 'Job', accessor: (j) => j.name, kind: 'text', wrap: true, priority: 'essential' },
    { id: 'module', header: 'Module', accessor: (j) => j.feature, kind: 'text', priority: 'low' },
    {
      id: 'status',
      header: 'Status',
      accessor: (j) => JOB_STATES[j.status],
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'completed',
      header: 'Last completed',
      accessor: (j) => momentText(j.lastEnd),
      cell: (j) => createElement(Moment, { at: j.lastEnd, now }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'lag',
      header: 'Lag',
      accessor: (j) => lagText(j.lagSeconds),
      cell: (j) => createElement(Lag, { seconds: j.lagSeconds }),
      kind: 'text',
      priority: 'high',
    },
    {
      id: 'health',
      header: 'Health',
      accessor: (j) => (j.degraded ? 'Degraded' : 'Healthy'),
      cell: (j) => createElement(Verdict, { degraded: j.degraded }),
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
  ];
}

/** The replicas' columns. The replica and its health are never hidden; its version and the clusters it owns go first. */
export function replicaColumns(): Column<ReplicaHealth>[] {
  return [
    {
      id: 'replica',
      header: 'Replica',
      accessor: (r) => `${r.host}${r.self ? ' This replica' : ''} ${r.id.slice(0, 8)}`,
      cell: (r) => createElement(ReplicaName, { replica: r }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    { id: 'version', header: 'Version', accessor: (r) => r.version, kind: 'text', priority: 'low' },
    {
      id: 'state',
      header: 'State',
      accessor: (r) => `${REPLICA_STATES[r.state].label} ${REPLICA_STATES[r.state].detail ?? ''}`.trim(),
      cell: (r) => createElement(ReplicaState, { state: r.state }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    { id: 'heartbeat', header: 'Last heartbeat', accessor: heartbeatText, kind: 'time', priority: 'high' },
    {
      id: 'clusters',
      header: 'Clusters owned',
      accessor: (r) => (r.ownedClusters.length > 0 ? r.ownedClusters.map((c) => c.name).join(', ') : 'None'),
      kind: 'text',
      wrap: true,
      priority: 'low',
    },
    {
      id: 'health',
      header: 'Health',
      accessor: (r) => (r.state === 'STOPPED' ? 'Not running' : r.degraded ? 'Degraded' : 'Healthy'),
      cell: (r) => createElement(ReplicaHealthCell, { replica: r }),
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
  ];
}

/** The broker nodes' columns. The node and its health are never hidden; the rate-limit wait is the first to go. */
export function nodeColumns(now: number): Column<NodeHealth>[] {
  return [
    {
      id: 'node',
      header: 'Node',
      accessor: (n) => `${n.name} ${n.node ?? 'No management address'}`,
      cell: (n) => createElement(NodeName, { node: n }),
      kind: 'text',
      wrap: true,
      priority: 'essential',
    },
    {
      id: 'success',
      header: 'Last success',
      accessor: (n) => momentText(n.lastSuccess),
      cell: (n) => createElement(Moment, { at: n.lastSuccess, now }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'failure',
      header: 'Last failure',
      accessor: (n) => `${momentText(n.lastFailure)}${n.lastError ? ` ${n.lastError}` : ''}`,
      cell: (n) => createElement(LastFailure, { node: n, now }),
      kind: 'text',
      wrap: true,
      priority: 'high',
    },
    {
      id: 'latency',
      header: 'Call latency (p95)',
      accessor: (n) => figureText(round(n.managementP95Millis), 'ms'),
      cell: (n) => createElement(Figure, { value: round(n.managementP95Millis), unit: 'ms' }),
      kind: 'number',
      priority: 'high',
    },
    {
      id: 'wait',
      header: 'Rate-limit wait',
      accessor: (n) => figureText(n.rateLimitWaitMillis, 'ms'),
      cell: (n) => createElement(Figure, { value: n.rateLimitWaitMillis, unit: 'ms' }),
      kind: 'number',
      priority: 'low',
    },
    {
      id: 'health',
      header: 'Health',
      accessor: (n) => (n.degraded ? 'Degraded' : 'Healthy'),
      cell: (n) => createElement(Verdict, { degraded: n.degraded }),
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
  ];
}

/** What a key versions table needs from its view: the state in words and how many secrets each version wraps. */
export interface KeyVersionRows {
  state: (version: number) => string;
  stored: (version: number) => number;
}

/** The key versions' columns: the version is never hidden, nor the state that says what to do about it. */
export function keyVersionColumns({ state, stored }: KeyVersionRows): Column<number>[] {
  return [
    { id: 'version', header: 'Key version', accessor: (v) => v, kind: 'number', priority: 'essential' },
    { id: 'state', header: 'State', accessor: state, kind: 'text', wrap: true, priority: 'essential' },
    { id: 'stored', header: 'Stored secrets', accessor: stored, kind: 'number', priority: 'essential' },
  ];
}

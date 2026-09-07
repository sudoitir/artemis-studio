import { useMemo, useState } from 'react';
import { Alert, Button, Group, Stack, Switch, Text, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import {
  useCluster,
  useQueues,
  useSqlPlan,
  type ApiError,
  type SqlResultView,
  type SqlRowView,
} from '../api/client.ts';
import { useCan } from '../auth/useCan.ts';
import { CapabilityGate } from '../shared/CapabilityGate.tsx';
import { gateFor } from '../shared/capabilityGate.ts';
import { OutcomeSummary, type OutcomeRow } from '../shared/NodeOutcomeSummary.tsx';
import { CapabilityLedger } from '../clusters/CapabilityLedger.tsx';
import { MessageDetailPanel } from '../messages/MessageDetailPanel.tsx';
import { QueryEditor } from './QueryEditor.tsx';
import { ExplainStrip } from './ExplainStrip.tsx';
import { ResultGrid } from './ResultGrid.tsx';
import { SyntaxHelp } from './SyntaxHelp.tsx';
import { LiveTailBanner } from './LiveTailBanner.tsx';
import { useSqlTail } from './useSqlTail.ts';
import { boundWords, noticeWords } from './notices.ts';
import classes from './SqlConsoleView.module.css';

/** How many queue names to offer the editor's completion. Enough to cover a cluster. */
const QUEUE_COMPLETION_LIMIT = 500;

const STARTER = 'SELECT *\nFROM "ORDER.IN"\nORDER BY timestamp DESC\nLIMIT 100';

/** A node's contribution to a result, in the words a query uses rather than a command's. */
function outcomeRows(nodes: SqlResultView['nodes']): OutcomeRow[] {
  return (nodes ?? []).map((node, i) => {
    const status =
      node.status === 'ANSWERED'
        ? `answered · examined ${(node.examined ?? 0).toLocaleString()}`
        : node.status === 'FAILED'
          ? 'failed'
          : 'not read';
    return {
      key: `${node.nodeId}/${node.queueName}/${i}`,
      name: `${node.nodeName} · ${node.queueName}`,
      count: (node.matched ?? 0).toLocaleString(),
      status,
      tone: node.status === 'FAILED' ? 'danger' : node.status === 'NOT_READ' ? 'warning' : undefined,
      detail: node.detail,
    };
  });
}

/** The headline: the shape of the result, before any row of it is read. */
function verdictFor(
  result: SqlResultView,
  rowCount: number,
): { text: string; tone?: 'warning' | 'danger' } {
  const nodes = result.nodes ?? [];
  const answered = nodes.filter((n) => n.status === 'ANSWERED').length;

  if (answered === 0 && nodes.length > 0) {
    return { text: 'No node answered — this result is not an answer', tone: 'danger' };
  }
  if (result.partial) {
    return {
      text: `${rowCount.toLocaleString()} row${rowCount === 1 ? '' : 's'} — incomplete, this is a prefix of the answer`,
      tone: 'warning',
    };
  }
  return {
    text: `${rowCount.toLocaleString()} row${rowCount === 1 ? '' : 's'} from ${answered} of ${nodes.length} targets`,
  };
}

/**
 * The SQL Console (ADR-0058): one query language over messages, across many
 * queues, with the plan stated before the query runs and the provenance of every
 * row stated after.
 *
 * <p>The query text and whether the tail is running are URL state, so a console
 * can be linked to a colleague and opened in the state it was left. The source is
 * not a third parameter: it is the `FROM` qualifier inside the text, and giving
 * one fact two owners is how they drift apart. Editor cursor and selection stay
 * local.
 */
export function SqlConsoleView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { q?: string; live?: boolean };
  const navigate = useNavigate();

  const [text, setText] = useState(search.q ?? STARTER);
  const [live, setLive] = useState(search.live ?? false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [openRow, setOpenRow] = useState<SqlRowView | null>(null);
  const [debounced] = useDebouncedValue(text, 400);

  const cluster = useCluster(clusterId);
  const queues = useQueues(clusterId, { size: QUEUE_COMPLETION_LIMIT });
  const { can, loading: grantsLoading } = useCan();
  const plan = useSqlPlan(clusterId, debounced);
  const run = useSqlTail(clusterId);

  const queueNames = useMemo(
    () => Array.from(new Set((queues.data?.data ?? []).map((q) => q.queueName))).sort(),
    [queues.data],
  );

  const gate = gateFor(
    can('message:read', clusterId),
    'Browse messages',
    cluster.data?.capabilities.messageIo,
    grantsLoading || cluster.isPending,
  );

  const execute = (tailing = live) => {
    if (gate.kind === 'blocked' || !text.trim()) return;
    // The shareable state is written when the query is actually run, not on every
    // keystroke — a history entry per character would make Back useless.
    navigate({ to: '.', search: () => ({ q: text, live: tailing || undefined }) });
    run.start(text, tailing);
  };

  const toggleLive = (next: boolean) => {
    setLive(next);
    // The tail rides the same stream as the static run, so changing the mode means
    // reopening it. Doing that here rather than waiting for another Run is what
    // makes the switch mean what it says.
    if (run.status !== 'idle') execute(next);
  };

  const stopTail = () => {
    setLive(false);
    run.stop();
    navigate({ to: '.', search: () => ({ q: text }) });
  };

  const { result, rows, status, error } = run;
  const running = status === 'running';
  const tailing = status === 'tailing';
  const verdict = result ? verdictFor(result, rows.length) : null;

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-end">
        <Stack gap={0}>
          <Title order={3}>SQL Console</Title>
          <Text size="xs" c="dimmed">
            Search messages across this cluster&apos;s queues. Read-only.
          </Text>
        </Stack>
        <Group gap="xs">
          <Switch
            size="xs"
            label="Live tail"
            checked={live}
            onChange={(e) => toggleLive(e.currentTarget.checked)}
            disabled={gate.kind === 'blocked'}
          />
          <Button size="xs" variant="default" onClick={() => setHelpOpen(true)}>
            Syntax and examples
          </Button>
          <CapabilityGate verdict={gate}>
            <Button
              size="xs"
              onClick={() => execute()}
              loading={running}
              disabled={gate.kind === 'blocked' || !text.trim()}
            >
              {live ? 'Run and tail' : 'Run'}
            </Button>
          </CapabilityGate>
        </Group>
      </Group>

      {gate.kind === 'allowed' && gate.uncertain ? (
        <Alert color="gray" variant="light" title="Not yet established for this connection">
          No management read has been attempted here yet, so Studio cannot say for certain that
          browsing will work. The console is offered anyway — the first query settles it.
        </Alert>
      ) : null}

      <QueryEditor value={text} onChange={setText} onRun={() => execute()} queues={queueNames} />
      <div className={classes.toolbar}>
        <span className={classes.hint}>⌘/Ctrl-Enter runs · Ctrl-Space completes</span>
      </div>

      <ExplainStrip plan={plan.data} error={plan.error ?? null} pending={plan.isFetching} />

      {gate.kind === 'blocked' && cluster.data ? (
        <Stack gap="xs">
          <Alert color="yellow" variant="light" title="This console cannot read messages here">
            {gate.reason}
          </Alert>
          <CapabilityLedger capabilities={cluster.data.capabilities} />
        </Stack>
      ) : null}

      {/* Every outcome is announced, not only rendered: an operator on a screen
          reader otherwise gets no signal that a query they ran has finished. */}
      <div className={classes.announcement} role="status" aria-live="polite">
        {running
          ? 'Running query'
          : status === 'failed' && error
            ? `Query failed: ${error.message}`
            : status === 'disconnected'
              ? 'The connection to this query was lost. Run it again.'
              : tailing
                ? `Live tail running. ${rows.length} rows so far.`
                : (verdict?.text ?? '')}
      </div>

      {status === 'failed' && error ? <QueryFailure error={error} /> : null}

      {status === 'disconnected' ? (
        <Alert color="yellow" variant="light" title="The connection to this query was lost">
          <Stack gap={4}>
            <Text size="sm">
              Studio does not silently reopen it: this query fans out across brokers and is
              audited, and reconnecting would run it a second time without you asking.
            </Text>
            <Group>
              <Button size="xs" onClick={() => execute()}>
                Run it again
              </Button>
            </Group>
          </Stack>
        </Alert>
      ) : null}

      {tailing ? <LiveTailBanner tail={run.tail} shown={rows.length} onStop={stopTail} /> : null}

      {result || rows.length > 0 ? (
        <Stack gap="sm">
          {/* Provenance, once per result rather than only per row: an operator
              scanning a table reads the rows, not the badge column, and an
              indexed row is a record of an observation rather than of a queue. */}
          {result?.plan?.source === 'INDEX' ? (
            <Alert
              color="gray"
              variant="light"
              title="Answered from the index, not from the brokers"
            >
              <Text size="sm">
                These rows are what Studio observed while sampling these queues. A message may
                have been consumed since it was seen, and one that arrived and left between two
                samples was never indexed at all. Verify on broker, on any row, asks a broker
                whether it is still there.
              </Text>
            </Alert>
          ) : null}
          {(result?.boundsReached ?? []).map((bound, i) => (
            <Alert
              key={`${bound.kind}-${i}`}
              color="yellow"
              variant="light"
              title="This result is incomplete"
            >
              <Text size="sm">{boundWords(bound)}</Text>
            </Alert>
          ))}
          {(result?.notices ?? []).map((notice, i) => {
            const { text: words, tone } = noticeWords(notice);
            return (
              <Alert
                key={`${notice.kind}-${i}`}
                variant="light"
                color={tone === 'warning' ? 'yellow' : 'gray'}
              >
                <Text size="sm">{words}</Text>
              </Alert>
            );
          })}

          {verdict ? (
            <OutcomeSummary
              verdict={verdict.text}
              verdictTone={verdict.tone}
              rows={outcomeRows(result?.nodes)}
            />
          ) : (
            <OutcomeSummary
              verdict={`Running — ${rows.length.toLocaleString()} rows so far`}
              rows={outcomeRows(run.progress)}
            />
          )}

          <ResultGrid
            clusterId={clusterId}
            rows={rows}
            onOpen={setOpenRow}
            freshKeys={run.freshKeys}
            emptyLabel={
              result ? <EmptyResult result={result} tailing={tailing} /> : <Text>Running…</Text>
            }
          />
        </Stack>
      ) : null}

      <SyntaxHelp opened={helpOpen} onClose={() => setHelpOpen(false)} onLoadExample={setText} />

      {/* Identity is (node, queue, messageId), so the detail request carries all
          three. An indexed row may already be gone from the broker — the panel
          reports that as the read failure it is, rather than as an empty message. */}
      <MessageDetailPanel
        clusterId={clusterId}
        queueName={openRow?.queueName ?? ''}
        messageId={openRow ? String(openRow.messageId) : null}
        node={openRow?.nodeId}
        onClose={() => setOpenRow(null)}
      />
    </Stack>
  );
}

/** A failure states its cause and what to do next. "Something went wrong" is not shippable. */
function QueryFailure({ error }: { error: ApiError }) {
  const estimate = typeof error.problem.estimate === 'number' ? error.problem.estimate : undefined;
  const ceiling = typeof error.problem.ceiling === 'number' ? error.problem.ceiling : undefined;
  const hint = typeof error.problem.hint === 'string' ? error.problem.hint : undefined;

  return (
    <Alert color="red" variant="light" title={error.title}>
      <Stack gap={4}>
        <Text size="sm">{error.message}</Text>
        {estimate != null && ceiling != null ? (
          <Text size="sm">
            It would examine about {estimate.toLocaleString()} messages, against a ceiling of{' '}
            {ceiling.toLocaleString()}.
          </Text>
        ) : null}
        <Text size="sm">
          {hint ??
            (error.status === 429
              ? 'Wait for one of your running queries to finish, then run this one again.'
              : 'Narrow the query — a header or property predicate is evaluated by the broker and costs nothing.')}
        </Text>
      </Stack>
    </Alert>
  );
}

/**
 * Why there is nothing here. An empty grid means at least five different things,
 * and four of them are not "the queue is empty" — presenting an absence as a fact
 * is the failure this whole screen exists to avoid.
 */
function EmptyResult({ result, tailing }: { result: SqlResultView; tailing: boolean }) {
  const nodes = result.nodes ?? [];
  const unread = nodes.filter((n) => n.status !== 'ANSWERED');
  const noQueue = (result.plan?.targets ?? []).length === 0;

  if (noQueue) {
    return (
      <Stack gap={4}>
        <Text fw={600}>No queue matched</Text>
        <Text size="sm">
          The FROM pattern matched no queue on any node in this cluster. This is an empty target
          list, not an empty queue — check the name, and remember that <code>*</code> matches one
          dot-delimited level and <code>#</code> matches many.
        </Text>
      </Stack>
    );
  }
  if (unread.length > 0) {
    return (
      <Stack gap={4}>
        <Text fw={600}>Nothing to show, and not because there was nothing</Text>
        <Text size="sm">
          {unread.length} of {nodes.length} targets did not answer, so this is not an empty result —
          it is an unknown one. The per-node summary above names them.
        </Text>
      </Stack>
    );
  }
  if (tailing) {
    return (
      <Stack gap={4}>
        <Text fw={600}>Nothing matched yet</Text>
        <Text size="sm">
          Every target answered and none of them held a matching message when the tail started.
          Anything that arrives from now on appears here — but only if it is still on the queue
          when Studio next reads it.
        </Text>
      </Stack>
    );
  }
  return (
    <Stack gap={4}>
      <Text fw={600}>No message matched</Text>
      <Text size="sm">
        Every target answered and none of them held a message your predicates accept. Widen the
        query, or check whether a body predicate is being defeated by a truncated body.
      </Text>
    </Stack>
  );
}

import { useId, useMemo, useRef, useState } from 'react';
import { Button, Splitter } from '@mantine/core';
import { useDebouncedValue, useElementSize, useHotkeys } from '@mantine/hooks';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { CapabilityLedger, useCluster } from '../clusters/index.ts';
import { useQueues } from '../queues/index.ts';
import { MessageDetailPanel } from '../messages/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import type { ApiError } from '../../kernel/api/request.ts';
import { gateFor } from '../../ui/capabilityGate.ts';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { useSqlPlan, type SqlRowView } from './api.ts';
import { costVerdict } from './costVerdict.ts';
import { HistoryMenu } from './HistoryMenu.tsx';
import { clearHistory } from './queryHistory.ts';
import type { QueryError } from './QueryEditor.tsx';
import { QUEUE_COMPLETION_LIMIT, QueryPane, QueryToolbar } from './QueryPane.tsx';
import { ResultPane } from './ResultPane.tsx';
import { SyntaxHelp } from './SyntaxHelp.tsx';
import { useQueryHistory } from './useQueryHistory.ts';
import { useSqlTail } from './useSqlTail.ts';
import classes from './SqlConsoleView.module.css';

const STARTER = 'SELECT *\nFROM "ORDER.IN"\nORDER BY timestamp DESC\nLIMIT 100';

/** The editor's and the results' share of the workspace, in %, remembered in this browser. */
const SPLIT_KEY = 'as:sql:split';
const DEFAULT_SPLIT = [36, 64];
const EDITOR_MIN = 20;
const RESULTS_MIN = 25;

/**
 * What each pane must hold, in rem: the editor's pane its label, three lines of query, the key hint and
 * the cost line, so what a query will read stays in view however far the operator drags; the results a
 * few rows. The split works in shares, which a screen reader announces as percent, so these become the
 * share they need of the workspace as measured, and never less than the shares above.
 */
const EDITOR_FLOOR_REM = 15;
const RESULTS_FLOOR_REM = 10;

function shareOf(rem: number, height: number, least: number): number {
  if (height <= 0) return least;
  const px = rem * Number.parseFloat(getComputedStyle(document.documentElement).fontSize);
  return Math.max(least, Math.ceil((px / height) * 100));
}

function validSplit(sizes: unknown): sizes is number[] {
  return (
    Array.isArray(sizes) &&
    sizes.length === 2 &&
    sizes.every((n) => typeof n === 'number' && Number.isFinite(n)) &&
    sizes[0] >= EDITOR_MIN &&
    sizes[1] >= RESULTS_MIN
  );
}

/** The split this browser remembered, or the default when it holds none or one that cannot be trusted. */
function readSplit(): number[] {
  try {
    const stored: unknown = JSON.parse(globalThis.localStorage.getItem(SPLIT_KEY) ?? 'null');
    return validSplit(stored) ? stored : DEFAULT_SPLIT;
  } catch {
    return DEFAULT_SPLIT;
  }
}

/** Written as the separator moves, from the pointer or the keys, and not held in state: a drag is not a re-render. */
function rememberSplit(sizes: unknown[]) {
  if (!validSplit(sizes)) return;
  try {
    globalThis.localStorage.setItem(SPLIT_KEY, JSON.stringify(sizes));
  } catch {
    // The split holds for this visit; remembering it is a convenience.
  }
}

/** The token a rejection is about, with the words to say beside it; null when the error names no token. */
function queryErrorOf(error: ApiError | null | undefined): QueryError | null {
  const { offending, suggestion } = error?.problem ?? {};
  if (typeof offending !== 'string' || !offending) return null;
  const didYouMean = typeof suggestion === 'string' && suggestion ? ` Did you mean ${suggestion}?` : '';
  return { token: offending, message: `${error?.message ?? ''}${didYouMean}` };
}

/**
 * The SQL Console (ADR-0058): one query language over messages, across many queues, with the cost stated
 * before the query runs and the provenance of every row stated after.
 *
 * <p>It is one workspace: the editor above and the results below, divided by a split the operator
 * resizes with the pointer or the keyboard and which is remembered per browser. The query text and
 * whether the tail is running are URL state, so a console can be linked to a colleague and opened in the
 * state it was left. The source is not a third parameter: it is the `FROM` qualifier inside the text, and
 * giving one fact two owners is how they drift apart. Editor cursor and selection stay local.
 */
export function SqlConsoleView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as { q?: string; live?: boolean };
  const navigate = useNavigate();
  const costId = useId();
  const toolbar = useRef<HTMLDivElement>(null);
  const workspace = useElementSize();
  const editorMin = shareOf(EDITOR_FLOOR_REM, workspace.height, EDITOR_MIN);
  const resultsMin = Math.min(100 - editorMin, shareOf(RESULTS_FLOOR_REM, workspace.height, RESULTS_MIN));

  const [text, setText] = useState(search.q ?? STARTER);
  const [live, setLive] = useState(search.live ?? false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [openRow, setOpenRow] = useState<SqlRowView | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [debounced] = useDebouncedValue(text, 400);
  const [split] = useState(readSplit);

  const cluster = useCluster(clusterId);
  const queues = useQueues(clusterId, { size: QUEUE_COMPLETION_LIMIT });
  const { can, loading: grantsLoading } = useCan();
  const run = useSqlTail(clusterId);
  const [history, setHistory] = useQueryHistory(run);

  const queueNames = useMemo(
    () => Array.from(new Set((queues.data?.data ?? []).map((q) => q.queueName))).sort((a, b) => a.localeCompare(b)),
    [queues.data],
  );
  // An absent completion entry reads as "there is no such queue". Say when the list is short because it was
  // capped rather than because the cluster is small.
  const completionCapped = (queues.data?.data ?? []).length >= QUEUE_COMPLETION_LIMIT;

  const gate = gateFor(
    can('message:read', clusterId),
    'Browse messages',
    cluster.data?.capabilities.messageIo,
    grantsLoading || cluster.isPending,
  );

  // Planning is a server call carrying the query text, and a blocked gate means the server will refuse it.
  // Asking anyway renders a 403 in the cost line, which reads as "your query is wrong" rather than "you
  // may not read messages here".
  const plan = useSqlPlan(clusterId, gate.kind === 'blocked' ? '' : debounced);
  const verdict = costVerdict({
    text,
    debounced,
    blockedReason: gate.kind === 'blocked' ? gate.reason : null,
    plan: plan.data,
    planError: plan.error,
    planPending: plan.isFetching,
  });

  const execute = (tailing = live) => {
    if (gate.kind === 'blocked' || !text.trim()) return;
    // The shareable state is written when the query is actually run, not on every keystroke: a history
    // entry per character would make Back useless.
    void navigate({ to: '.', search: () => ({ q: text, live: tailing || undefined }) });
    run.start(text, tailing);
  };

  const toggleLive = (next: boolean) => {
    setLive(next);
    // The tail rides the same stream as the static run, so changing the mode means reopening it. Doing that
    // here rather than waiting for another Run is what makes the switch mean what it says.
    if (run.status !== 'idle') execute(next);
  };

  const running = run.status === 'running';
  const cancellable = running || run.status === 'tailing';
  const cancel = () => {
    if (!cancellable) return;
    // Stopping a tail also takes it out of the address, so a link to the console does not reopen it tailing.
    if (run.status === 'tailing') {
      setLive(false);
      void navigate({ to: '.', search: () => ({ q: text }) });
    }
    run.cancel();
  };
  // In the editor Mod+. is the editor's own key; here it is the page's. Escape is never bound to this.
  useHotkeys([['mod+.', cancel]]);

  return (
    <Page fill>
      <PageHeader title="SQL Console" description="Search messages across this cluster's queues. Read-only." />

      {gate.kind === 'blocked' ? (
        <>
          <Notice tone="warning" title="This console cannot read messages here">
            {gate.reason}
          </Notice>
          {cluster.data ? <CapabilityLedger capabilities={cluster.data.capabilities} clusterId={clusterId} /> : null}
        </>
      ) : null}
      {gate.kind === 'allowed' && gate.uncertain ? (
        <Notice title="Not yet established for this connection">
          No message operation has been attempted here yet, so Studio cannot say for certain that browsing will work.
          The console is offered anyway rather than blocked on the absence of evidence.
        </Notice>
      ) : null}

      <SyntaxHelp opened={helpOpen} onClose={() => setHelpOpen(false)} onLoadExample={setText} />

      {/* Identity is (node, queue, messageId), so the detail request carries all three. An indexed row may
          already be gone from the broker — the panel reports that as the read failure it is, rather than as
          an empty message. */}
      <MessageDetailPanel
        clusterId={clusterId}
        queueName={openRow?.queueName ?? ''}
        messageId={openRow ? String(openRow.messageId) : null}
        node={openRow?.nodeId}
        onClose={() => setOpenRow(null)}
      />

      <QueryToolbar
        toolbarRef={toolbar}
        text={text}
        onRun={() => execute()}
        onCancel={cancel}
        gate={gate}
        live={live}
        onLive={toggleLive}
        running={running}
        cancellable={cancellable}
        history={
          <>
            <HistoryMenu
              history={history}
              opened={historyOpen}
              onOpenChange={setHistoryOpen}
              onLoad={(sql) => {
                // Loaded, not run. Re-running a fan-out because someone opened a menu is not
                // something to do on their behalf.
                setText(sql);
                setHistoryOpen(false);
              }}
              onClear={() => setHistory(clearHistory())}
            />
            <Button size="xs" variant="default" onClick={() => setHelpOpen(true)}>
              Syntax and examples
            </Button>
          </>
        }
        costId={costId}
      />

      <div className={classes.frame} ref={workspace.ref}>
        <Splitter
          orientation="vertical"
          className={classes.workspace}
          classNames={{ pane: classes.pane }}
          step={5}
          shiftStep={10}
          lineSize={1}
          handleColor="var(--as-border)"
          onSizeChange={rememberSplit}
          attributes={{ handle: { 'aria-label': 'Resize the editor and the results' } }}
        >
          <Splitter.Pane defaultSize={split[0]} min={editorMin}>
            <QueryPane
              text={text}
              onText={setText}
              onRun={() => execute()}
              onCancel={cancel}
              onEscape={() =>
                toolbar.current?.querySelector<HTMLElement>('button:not(:disabled), input:not(:disabled)')?.focus()
              }
              queueNames={queueNames}
              completionCapped={completionCapped}
              costId={costId}
              verdict={verdict}
              plan={plan.data}
              queryError={queryErrorOf(plan.error) ?? queryErrorOf(run.error)}
            />
          </Splitter.Pane>
          <Splitter.Pane defaultSize={split[1]} min={resultsMin}>
            <ResultPane
              clusterId={clusterId}
              run={run}
              onOpenRow={setOpenRow}
              onRunAgain={() => execute()}
              onStopTail={cancel}
            />
          </Splitter.Pane>
        </Splitter>
      </div>
    </Page>
  );
}

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Alert,
  Button,
  Checkbox,
  Group,
  Menu,
  Stack,
  Switch,
  Text,
  Title,
} from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useNavigate, useParams, useSearch } from "@tanstack/react-router";

import {
  useCluster,
  useQueues,
  useSqlPlan,
  type ApiError,
  type SqlResultView,
  type SqlRowView,
} from "../api/client.ts";
import { useCan } from "../auth/useCan.ts";
import { CapabilityGate } from "../shared/CapabilityGate.tsx";
import { gateFor } from "../shared/capabilityGate.ts";
import {
  OutcomeSummary,
  type OutcomeRow,
} from "../shared/NodeOutcomeSummary.tsx";
import { CapabilityLedger } from "../clusters/CapabilityLedger.tsx";
import { MessageDetailPanel } from "../messages/MessageDetailPanel.tsx";
import { QueryEditor } from "./QueryEditor.tsx";
import { ExplainStrip } from "./ExplainStrip.tsx";
import { ResultGrid } from "./ResultGrid.tsx";
import { ALL_COLUMN_IDS, COLUMN_LABELS } from "./resultColumns.ts";
import { ResultMetaBar } from "./ResultMetaBar.tsx";
import { SyntaxHelp } from "./SyntaxHelp.tsx";
import { LiveTailBanner } from "./LiveTailBanner.tsx";
import { useSqlTail } from "./useSqlTail.ts";
import { download, toCsv, toJson } from "./exportRows.ts";
import {
  clearHistory,
  entryFor,
  readHistory,
  recordHistory,
  type HistoryEntry,
} from "./queryHistory.ts";
import classes from "./SqlConsoleView.module.css";

/** How many queue names to offer the editor's completion. Enough to cover a cluster. */
const QUEUE_COMPLETION_LIMIT = 500;

/**
 * How tall the editor pane is, as a fraction of the console. A per-viewer
 * convenience, so `localStorage` is the right home and losing it costs nothing —
 * it is not something a shared URL should carry.
 */
const EDITOR_FRACTION_KEY = "artemis-studio.sql.editorFraction";
const MIN_FRACTION = 0.15;
const MAX_FRACTION = 0.7;

function readFraction(): number {
  try {
    const raw = window.localStorage.getItem(EDITOR_FRACTION_KEY);
    const parsed = raw ? Number.parseFloat(raw) : Number.NaN;
    return Number.isFinite(parsed)
      ? Math.min(MAX_FRACTION, Math.max(MIN_FRACTION, parsed))
      : 0.32;
  } catch {
    return 0.32;
  }
}

/** The columns shown, in order. Also per-viewer, for the same reason. */
const COLUMNS_KEY = "artemis-studio.sql.columns";

function readColumns(): string[] {
  try {
    const raw = window.localStorage.getItem(COLUMNS_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    if (!Array.isArray(parsed)) return [...ALL_COLUMN_IDS];
    const known = parsed.filter((id): id is string =>
      ALL_COLUMN_IDS.includes(id as never),
    );
    return known.length > 0 ? known : [...ALL_COLUMN_IDS];
  } catch {
    return [...ALL_COLUMN_IDS];
  }
}

const STARTER = 'SELECT *\nFROM "ORDER.IN"\nORDER BY timestamp DESC\nLIMIT 100';

/** A node's contribution to a result, in the words a query uses rather than a command's. */
function outcomeRows(nodes: SqlResultView["nodes"]): OutcomeRow[] {
  return (nodes ?? []).map((node, i) => {
    const status =
      node.status === "ANSWERED"
        ? `answered · examined ${(node.examined ?? 0).toLocaleString()}`
        : node.status === "FAILED"
          ? "failed"
          : "not read";
    return {
      key: `${node.nodeId}/${node.queueName}/${i}`,
      name: `${node.nodeName} · ${node.queueName}`,
      count: (node.matched ?? 0).toLocaleString(),
      status,
      tone:
        node.status === "FAILED"
          ? "danger"
          : node.status === "NOT_READ"
            ? "warning"
            : undefined,
      detail: node.detail,
    };
  });
}

/** The headline: the shape of the result, before any row of it is read. */
function verdictFor(
  result: SqlResultView,
  rowCount: number,
): { text: string; tone?: "warning" | "danger" } {
  const nodes = result.nodes ?? [];
  const answered = nodes.filter((n) => n.status === "ANSWERED").length;

  if (answered === 0 && nodes.length > 0) {
    return {
      text: "No node answered — this result is not an answer",
      tone: "danger",
    };
  }
  if (result.partial) {
    return {
      text: `${rowCount.toLocaleString()} row${rowCount === 1 ? "" : "s"} — incomplete, this is a prefix of the answer`,
      tone: "warning",
    };
  }
  return {
    text: `${rowCount.toLocaleString()} row${rowCount === 1 ? "" : "s"} from ${answered} of ${nodes.length} targets`,
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
  const [editorFraction, setEditorFraction] = useState(readFraction);
  const [columnIds, setColumnIds] = useState<string[]>(readColumns);
  const [history, setHistory] = useState<HistoryEntry[]>(readHistory);
  const [historyOpen, setHistoryOpen] = useState(false);
  const splitRef = useRef<HTMLDivElement>(null);

  const cluster = useCluster(clusterId);
  const queues = useQueues(clusterId, { size: QUEUE_COMPLETION_LIMIT });
  const { can, loading: grantsLoading } = useCan();
  const run = useSqlTail(clusterId);

  const queueNames = useMemo(
    () =>
      Array.from(
        new Set((queues.data?.data ?? []).map((q) => q.queueName)),
      ).sort(),
    [queues.data],
  );
  // An absent completion entry reads as "there is no such queue". Say when the list
  // is short because it was capped rather than because the cluster is small.
  const completionCapped =
    (queues.data?.data ?? []).length >= QUEUE_COMPLETION_LIMIT;

  const gate = gateFor(
    can("message:read", clusterId),
    "Browse messages",
    cluster.data?.capabilities.messageIo,
    grantsLoading || cluster.isPending,
  );

  // Planning is a server call carrying the query text, and a blocked gate means the
  // server will refuse it. Asking anyway renders a 403 in the plan strip, which reads
  // as "your query is wrong" rather than "you may not read messages here".
  const plan = useSqlPlan(clusterId, gate.kind === "blocked" ? "" : debounced);

  const execute = (tailing = live) => {
    if (gate.kind === "blocked" || !text.trim()) return;
    // The shareable state is written when the query is actually run, not on every
    // keystroke — a history entry per character would make Back useless.
    navigate({
      to: ".",
      search: () => ({ q: text, live: tailing || undefined }),
    });
    run.start(text, tailing);
  };

  const toggleLive = (next: boolean) => {
    setLive(next);
    // The tail rides the same stream as the static run, so changing the mode means
    // reopening it. Doing that here rather than waiting for another Run is what
    // makes the switch mean what it says.
    if (run.status !== "idle") execute(next);
  };

  // The split is dragged, not typed, so the handle is also a slider: an operator on
  // a keyboard resizes it with the arrow keys and hears the value.
  const nudge = useCallback((delta: number) => {
    setEditorFraction((prev) => {
      const next = Math.min(MAX_FRACTION, Math.max(MIN_FRACTION, prev + delta));
      try {
        window.localStorage.setItem(EDITOR_FRACTION_KEY, String(next));
      } catch {
        // A private window, or storage turned off. The pane still resizes.
      }
      return next;
    });
  }, []);

  const startDrag = (startY: number) => {
    const host = splitRef.current;
    if (!host) return;
    const rect = host.getBoundingClientRect();
    const startFraction = editorFraction;
    const move = (e: PointerEvent) => {
      const next = startFraction + (e.clientY - startY) / rect.height;
      setEditorFraction(Math.min(MAX_FRACTION, Math.max(MIN_FRACTION, next)));
    };
    const end = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", end);
      setEditorFraction((current) => {
        try {
          window.localStorage.setItem(EDITOR_FRACTION_KEY, String(current));
        } catch {
          // as above
        }
        return current;
      });
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", end);
  };

  const toggleColumn = (id: string) => {
    setColumnIds((prev) => {
      const next = prev.includes(id)
        ? prev.filter((c) => c !== id)
        : [...ALL_COLUMN_IDS].filter((c) => prev.includes(c) || c === id);
      try {
        window.localStorage.setItem(COLUMNS_KEY, JSON.stringify(next));
      } catch {
        // as above
      }
      return next;
    });
  };

  const moveColumn = (id: string, by: number) => {
    setColumnIds((prev) => {
      const at = prev.indexOf(id);
      const to = at + by;
      if (at < 0 || to < 0 || to >= prev.length) return prev;
      const next = [...prev];
      next.splice(to, 0, next.splice(at, 1)[0]);
      try {
        window.localStorage.setItem(COLUMNS_KEY, JSON.stringify(next));
      } catch {
        // as above
      }
      return next;
    });
  };

  const stopTail = () => {
    setLive(false);
    run.stop();
    navigate({ to: ".", search: () => ({ q: text }) });
  };

  const { result, rows, status, error } = run;
  const running = status === "running";
  const tailing = status === "tailing";
  const verdict = result ? verdictFor(result, rows.length) : null;
  const captured = result?.plan?.captured === true;

  // A finished run is what goes into the history, not a keystroke: the entry
  // records what the query actually returned, which is the part that makes it
  // worth going back to.
  const recorded = useRef<string | null>(null);
  useEffect(() => {
    if (!result || status === "running") return;
    const key = `${text}@${result.plan?.source ?? ""}@${rows.length}`;
    if (recorded.current === key) return;
    recorded.current = key;
    setHistory(recordHistory(entryFor(text, rows, result.plan?.source)));
  }, [result, status, rows, text]);

  // The token a refusal is about, underlined in the editor itself (7.10).
  const errorToken =
    (typeof plan.error?.problem.offending === "string"
      ? plan.error.problem.offending
      : null) ??
    (typeof error?.problem.offending === "string"
      ? error.problem.offending
      : null);

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
          {/* The reason a control is disabled hangs off the gate, not off the
              control: a disabled input takes no focus, so a hover-only explanation
              is unreachable for an operator on a keyboard. */}
          <CapabilityGate verdict={gate}>
            <Switch
              size="xs"
              label="Live tail"
              checked={live}
              onChange={(e) => toggleLive(e.currentTarget.checked)}
              disabled={gate.kind === "blocked"}
            />
          </CapabilityGate>
          <Menu
            shadow="md"
            width={340}
            opened={historyOpen}
            onChange={setHistoryOpen}
          >
            <Menu.Target>
              <Button size="xs" variant="default">
                History
              </Button>
            </Menu.Target>
            <Menu.Dropdown>
              {history.length === 0 ? (
                <Menu.Item disabled>
                  <Text size="xs">
                    Nothing yet. Queries you run on this browser are remembered
                    here — locally, never sent anywhere.
                  </Text>
                </Menu.Item>
              ) : (
                <>
                  {history.map((entry) => (
                    <Menu.Item
                      key={`${entry.at}-${entry.sql}`}
                      onClick={() => {
                        // Loaded, not run. Re-running a fan-out because someone
                        // opened a menu is not something to do on their behalf.
                        setText(entry.sql);
                        setHistoryOpen(false);
                      }}
                    >
                      <Text size="xs" lineClamp={2}>
                        {entry.sql}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {new Date(entry.at).toLocaleString()} ·{" "}
                        {entry.rowCount.toLocaleString()} row
                        {entry.rowCount === 1 ? "" : "s"}
                        {entry.source
                          ? ` · ${entry.source === "INDEX" ? "index" : "brokers"}`
                          : ""}
                      </Text>
                    </Menu.Item>
                  ))}
                  <Menu.Divider />
                  <Menu.Item onClick={() => setHistory(clearHistory())}>
                    <Text size="xs">Clear history</Text>
                  </Menu.Item>
                </>
              )}
            </Menu.Dropdown>
          </Menu>
          <Button size="xs" variant="default" onClick={() => setHelpOpen(true)}>
            Syntax and examples
          </Button>
          <CapabilityGate verdict={gate}>
            <Button
              size="xs"
              onClick={() => execute()}
              loading={running}
              disabled={gate.kind === "blocked" || !text.trim()}
            >
              {live ? "Run and tail" : "Run"}
            </Button>
          </CapabilityGate>
        </Group>
      </Group>

      {gate.kind === "allowed" && gate.uncertain ? (
        <Alert
          color="gray"
          variant="light"
          title="Not yet established for this connection"
        >
          No message operation has been attempted here yet, so Studio cannot say
          for certain that browsing will work. The console is offered anyway
          rather than blocked on the absence of evidence.
        </Alert>
      ) : null}

      <div
        className={classes.split}
        ref={splitRef}
        style={{ ["--as-editor-fraction" as string]: String(editorFraction) }}
      >
        <div className={classes.editorPane}>
          <QueryEditor
            value={text}
            onChange={setText}
            onRun={() => execute()}
            queues={queueNames}
            errorToken={errorToken}
          />
          <div className={classes.toolbar}>
            <span className={classes.hint}>
              ⌘/Ctrl-Enter runs · Ctrl-Space completes
              {completionCapped
                ? ` · completion lists the first ${QUEUE_COMPLETION_LIMIT.toLocaleString()} queues on this cluster, not all of them`
                : ""}
            </span>
          </div>

          <ExplainStrip
            plan={plan.data}
            error={plan.error ?? null}
            pending={plan.isFetching}
          />
        </div>

        {/* The handle is a slider as well as a drag target: a pointer-only resize
            is unreachable for an operator on a keyboard. */}
        <div
          className={classes.handle}
          role="slider"
          tabIndex={0}
          aria-label="Editor height"
          aria-orientation="vertical"
          aria-valuemin={Math.round(MIN_FRACTION * 100)}
          aria-valuemax={Math.round(MAX_FRACTION * 100)}
          aria-valuenow={Math.round(editorFraction * 100)}
          aria-valuetext={`Editor takes ${Math.round(editorFraction * 100)} percent of the console`}
          onPointerDown={(e) => {
            e.preventDefault();
            startDrag(e.clientY);
          }}
          onKeyDown={(e) => {
            if (e.key === "ArrowUp") {
              e.preventDefault();
              nudge(-0.03);
            } else if (e.key === "ArrowDown") {
              e.preventDefault();
              nudge(0.03);
            }
          }}
        />

        <div className={classes.resultPane}>
          {gate.kind === "blocked" && cluster.data ? (
            <Stack gap="xs">
              <Alert
                color="yellow"
                variant="light"
                title="This console cannot read messages here"
              >
                {gate.reason}
              </Alert>
              <CapabilityLedger capabilities={cluster.data.capabilities} clusterId={clusterId} />
            </Stack>
          ) : null}

          {/* Every outcome is announced, not only rendered: an operator on a screen
          reader otherwise gets no signal that a query they ran has finished. */}
          <div
            className={classes.announcement}
            role="status"
            aria-live="polite"
          >
            {running
              ? "Running query"
              : status === "failed" && error
                ? `Query failed: ${error.message}`
                : status === "disconnected"
                  ? "The connection to this query was lost. Run it again."
                  : tailing
                    ? `Live tail running. ${rows.length} rows so far.`
                    : (verdict?.text ?? "")}
          </div>

          {status === "failed" && error ? <QueryFailure error={error} /> : null}

          {status === "disconnected" ? (
            <Alert
              color="yellow"
              variant="light"
              title="The connection to this query was lost"
            >
              <Stack gap={4}>
                <Text size="sm">
                  Studio does not silently reopen it: this query fans out across
                  brokers and is audited, and reconnecting would run it a second
                  time without you asking.
                </Text>
                <Group>
                  <Button size="xs" onClick={() => execute()}>
                    Run it again
                  </Button>
                </Group>
              </Stack>
            </Alert>
          ) : null}

          {tailing ? (
            <LiveTailBanner
              tail={run.tail}
              shown={rows.length}
              discarding={run.discarding}
              captured={captured}
              paused={run.paused}
              buffered={run.buffered}
              onPause={run.pause}
              onResume={run.resume}
              onStop={stopTail}
            />
          ) : null}

          {result || rows.length > 0 ? (
            <Stack gap="sm" className={classes.resultStack}>
              {/* One bar, not a stack of alerts: every statement is preserved, behind
              a disclosure, and the rows stay on screen while they are read (7.4). */}
              {result && verdict ? (
                <ResultMetaBar
                  result={result}
                  rowCount={rows.length}
                  verdict={verdict}
                />
              ) : null}

              <Group gap="xs" justify="space-between" wrap="wrap">
                <Menu shadow="md" width={260} closeOnItemClick={false}>
                  <Menu.Target>
                    <Button size="compact-xs" variant="default">
                      Columns
                    </Button>
                  </Menu.Target>
                  <Menu.Dropdown>
                    {[...ALL_COLUMN_IDS].map((id) => (
                      <Menu.Item key={id} component="div">
                        <Group gap="xs" justify="space-between" wrap="nowrap">
                          <Checkbox
                            size="xs"
                            label={COLUMN_LABELS[id]}
                            checked={columnIds.includes(id)}
                            onChange={() => toggleColumn(id)}
                          />
                          {columnIds.includes(id) ? (
                            <Group gap={2}>
                              <Button
                                size="compact-xs"
                                variant="subtle"
                                aria-label={`Move ${COLUMN_LABELS[id]} earlier`}
                                onClick={() => moveColumn(id, -1)}
                              >
                                ↑
                              </Button>
                              <Button
                                size="compact-xs"
                                variant="subtle"
                                aria-label={`Move ${COLUMN_LABELS[id]} later`}
                                onClick={() => moveColumn(id, 1)}
                              >
                                ↓
                              </Button>
                            </Group>
                          ) : null}
                        </Group>
                      </Menu.Item>
                    ))}
                  </Menu.Dropdown>
                </Menu>

                <Group gap="xs">
                  <Text size="xs" c="dimmed">
                    Export writes the {rows.length.toLocaleString()} row
                    {rows.length === 1 ? "" : "s"} in view, which may be a
                    prefix of the answer.
                  </Text>
                  <Button
                    size="compact-xs"
                    variant="default"
                    disabled={rows.length === 0}
                    onClick={() =>
                      download(
                        "sql-console.csv",
                        toCsv(rows),
                        "text/csv;charset=utf-8",
                      )
                    }
                  >
                    CSV
                  </Button>
                  <Button
                    size="compact-xs"
                    variant="default"
                    disabled={rows.length === 0}
                    onClick={() =>
                      download(
                        "sql-console.json",
                        toJson(rows),
                        "application/json;charset=utf-8",
                      )
                    }
                  >
                    JSON
                  </Button>
                </Group>
              </Group>

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
                columnIds={columnIds}
                emptyLabel={
                  result ? (
                    <EmptyResult result={result} tailing={tailing} />
                  ) : (
                    <Text>Running…</Text>
                  )
                }
                onAtTopChange={(atTop) => {
                  // Scrolling away from the head of a live feed locks it: new rows are
                  // prepended, so without this the content moves under the operator
                  // exactly while they are trying to read it (7.9).
                  if (!tailing) return;
                  if (!atTop && !run.paused) run.pause();
                }}
              />
            </Stack>
          ) : null}
        </div>
      </div>

      <SyntaxHelp
        opened={helpOpen}
        onClose={() => setHelpOpen(false)}
        onLoadExample={setText}
      />

      {/* Identity is (node, queue, messageId), so the detail request carries all
          three. An indexed row may already be gone from the broker — the panel
          reports that as the read failure it is, rather than as an empty message. */}
      <MessageDetailPanel
        clusterId={clusterId}
        queueName={openRow?.queueName ?? ""}
        messageId={openRow ? String(openRow.messageId) : null}
        node={openRow?.nodeId}
        onClose={() => setOpenRow(null)}
      />
    </Stack>
  );
}

/** A failure states its cause and what to do next. "Something went wrong" is not shippable. */
function QueryFailure({ error }: { error: ApiError }) {
  const estimate =
    typeof error.problem.estimate === "number"
      ? error.problem.estimate
      : undefined;
  const ceiling =
    typeof error.problem.ceiling === "number"
      ? error.problem.ceiling
      : undefined;
  const hint =
    typeof error.problem.hint === "string" ? error.problem.hint : undefined;

  return (
    <Alert color="red" variant="light" title={error.title}>
      <Stack gap={4}>
        <Text size="sm">{error.message}</Text>
        {estimate != null && ceiling != null ? (
          <Text size="sm">
            It would examine about {estimate.toLocaleString()} messages, against
            a ceiling of {ceiling.toLocaleString()}.
          </Text>
        ) : null}
        <Text size="sm">
          {hint ??
            (error.status === 429
              ? "Wait for one of your running queries to finish, then run this one again."
              : "Narrow the query — a header or property predicate is evaluated by the broker and costs nothing.")}
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
function EmptyResult({
  result,
  tailing,
}: {
  result: SqlResultView;
  tailing: boolean;
}) {
  const nodes = result.nodes ?? [];
  const unread = nodes.filter((n) => n.status !== "ANSWERED");
  const noQueue = (result.plan?.targets ?? []).length === 0;

  if (noQueue) {
    return (
      <Stack gap={4}>
        <Text fw={600}>No queue matched</Text>
        <Text size="sm">
          The FROM pattern matched no queue on any node in this cluster. This is
          an empty target list, not an empty queue — check the name, and
          remember that <code>*</code> matches one dot-delimited level and{" "}
          <code>#</code> matches many.
        </Text>
      </Stack>
    );
  }
  if (unread.length > 0) {
    return (
      <Stack gap={4}>
        <Text fw={600}>Nothing to show, and not because there was nothing</Text>
        <Text size="sm">
          {unread.length} of {nodes.length} targets did not answer, so this is
          not an empty result — it is an unknown one. The per-node summary above
          names them.
        </Text>
      </Stack>
    );
  }
  if (tailing) {
    return (
      <Stack gap={4}>
        <Text fw={600}>Nothing matched yet</Text>
        <Text size="sm">
          Every target answered and none of them held a matching message when
          the tail started. Anything that arrives from now on appears here — but
          only if it is still on the queue when Studio next reads it.
        </Text>
      </Stack>
    );
  }
  return (
    <Stack gap={4}>
      <Text fw={600}>No message matched</Text>
      <Text size="sm">
        Every target answered and none of them held a message your predicates
        accept. Widen the query, or check whether a body predicate is being
        defeated by a truncated body.
      </Text>
    </Stack>
  );
}

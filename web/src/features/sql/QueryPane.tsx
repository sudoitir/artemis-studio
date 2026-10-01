import { useRef, type ReactNode } from 'react';
import { Button, Switch, Text } from '@mantine/core';

import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { Toolbar } from '../../ui/Toolbar.tsx';
import type { SqlPlanView } from './api.ts';
import type { CostVerdict } from './costVerdict.ts';
import { ExplainStrip } from './ExplainStrip.tsx';
import { QueryEditor, type QueryError } from './QueryEditor.tsx';
import classes from './SqlConsoleView.module.css';

/** How many queue names the editor's completion is offered. Enough to cover a cluster. */
export const QUEUE_COMPLETION_LIMIT = 500;

/**
 * Writing a query: the Query toolbar (Run and Cancel side by side, the live tail, the history and the
 * syntax help), the editor, and what the query will cost.
 *
 * <p>It is a named, focusable region because its content can be taller than the pane the split gives
 * it, and a scroll area the keyboard cannot reach is a defect. Escape in the editor, with nothing left to
 * close or collapse, moves focus to the first control of the toolbar; it never cancels, since cancelling
 * is Mod+. and a key that leaves the editor must not also stop a query that is reading brokers.
 */
export function QueryPane({
  text,
  onText,
  onRun,
  onCancel,
  gate,
  live,
  onLive,
  running,
  cancellable,
  history,
  queueNames,
  completionCapped,
  costId,
  verdict,
  plan,
  queryError,
}: Readonly<{
  text: string;
  onText: (next: string) => void;
  onRun: () => void;
  onCancel: () => void;
  gate: GateVerdict;
  live: boolean;
  onLive: (next: boolean) => void;
  running: boolean;
  /** A query is reading, or a tail is following: there is something to cancel. */
  cancellable: boolean;
  /** The History menu and the syntax help button, which sit at the end of the toolbar. */
  history: ReactNode;
  queueNames: string[];
  completionCapped: boolean;
  /** The id of the cost line, which describes the editor and Run. */
  costId: string;
  verdict: CostVerdict;
  plan: SqlPlanView | undefined;
  queryError: QueryError | null;
}>) {
  const toolbar = useRef<HTMLDivElement>(null);
  const blocked = gate.kind === 'blocked';

  return (
    <section className={classes.region} aria-label="Query and cost" tabIndex={0}>
      <div ref={toolbar}>
        <Toolbar
          label="Query"
          start={
            <>
              {/* The reason a control is disabled hangs off the gate, not off the control: a disabled
                  input takes no focus, so a hover-only explanation is unreachable for an operator on a
                  keyboard. */}
              <CapabilityGate verdict={gate} what="Run">
                <Button
                  size="xs"
                  onClick={onRun}
                  loading={running}
                  disabled={blocked || !text.trim()}
                  aria-describedby={costId}
                >
                  {live ? 'Run and tail' : 'Run'}
                </Button>
              </CapabilityGate>
              <Button size="xs" variant="default" onClick={onCancel} disabled={!cancellable}>
                Cancel
              </Button>
              <CapabilityGate verdict={gate} what="Live tail">
                <Switch
                  size="xs"
                  label="Live tail"
                  checked={live}
                  onChange={(e) => onLive(e.currentTarget.checked)}
                  disabled={blocked}
                />
              </CapabilityGate>
            </>
          }
          end={history}
        />
      </div>

      <QueryEditor
        value={text}
        onChange={onText}
        onRun={onRun}
        onCancel={onCancel}
        onEscape={() =>
          toolbar.current?.querySelector<HTMLElement>('button:not(:disabled), input:not(:disabled)')?.focus()
        }
        queues={queueNames}
        describedBy={costId}
        error={queryError}
      />

      <ExplainStrip id={costId} verdict={verdict} plan={plan} />

      <Text size="xs" c="dimmed">
        ⌘/Ctrl-Enter runs · ⌘/Ctrl-. cancels · Ctrl-Space completes · Escape leaves the editor
        {completionCapped
          ? ` · completion lists the first ${QUEUE_COMPLETION_LIMIT.toLocaleString()} queues on this cluster, not all of them`
          : ''}
      </Text>
    </section>
  );
}

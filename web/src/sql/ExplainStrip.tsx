import { Alert, Stack, Text } from "@mantine/core";

import type { ApiError, SqlPlanView } from "../api/client.ts";
import { noticeWords } from "./notices.ts";
import classes from "./ExplainStrip.module.css";

function Fact({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "warning";
}) {
  return (
    <div className={classes.fact}>
      <span className={classes.label}>{label}</span>
      <span className={classes.value} data-tone={tone}>
        {value}
      </span>
    </div>
  );
}

/** Extra properties the `sql-syntax` problem carries, so the error can point at a token. */
function syntaxDetail(error: ApiError): {
  offending?: string;
  suggestion?: string;
} {
  return {
    offending:
      typeof error.problem.offending === "string"
        ? error.problem.offending
        : undefined,
    suggestion:
      typeof error.problem.suggestion === "string"
        ? error.problem.suggestion
        : undefined,
  };
}

/**
 * What the query will do, worked out without contacting a broker — shown while
 * the operator is still typing, which is the only moment at which the cost of a
 * query is still cheap to change.
 *
 * <p>The estimate is always stated. An absent number reads as zero, and zero is
 * the most dangerous possible misreading of "how much will this examine".
 */
export function ExplainStrip({
  plan,
  error,
  pending,
}: {
  plan?: SqlPlanView;
  error: ApiError | null;
  pending: boolean;
}) {
  if (error) {
    const { offending, suggestion } = syntaxDetail(error);
    return (
      <Alert color="red" variant="light" title={error.title} role="status">
        <Stack gap={4}>
          <Text size="sm">{error.message}</Text>
          {offending ? (
            <Text size="sm">
              The problem is at <code>{offending}</code>.
            </Text>
          ) : null}
          {suggestion ? (
            <Text size="sm">Did you mean {suggestion}?</Text>
          ) : null}
        </Stack>
      </Alert>
    );
  }

  if (!plan) {
    return (
      <div className={classes.strip}>
        <Text size="sm" c="dimmed">
          {pending
            ? "Working out what this query will do…"
            : "Type a query and its plan appears here."}
        </Text>
      </div>
    );
  }

  const targets = plan.targets ?? [];
  const nodes = new Set(targets.map((t) => t.nodeId)).size;
  const queues = new Set(targets.map((t) => t.queueName)).size;
  const scanned = plan.scanned ?? [];
  const pushedDown = plan.pushedDown ?? [];

  return (
    <Stack gap="xs">
      <div className={classes.strip}>
        <Fact
          label="Source"
          value={plan.source === "INDEX" ? "historical index" : "live brokers"}
        />
        <Fact
          label="Reads"
          value={`${queues} queue${queues === 1 ? "" : "s"} on ${nodes} node${nodes === 1 ? "" : "s"}`}
        />
        <Fact
          label="Cost"
          value={
            plan.requiresScan
              ? `scan — examines about ${(plan.estimatedMessagesExamined ?? 0).toLocaleString()} messages`
              : "no scan — the broker filters"
          }
          tone={plan.requiresScan ? "warning" : undefined}
        />
        <Fact
          label="Row limit"
          value={(plan.effectiveLimit ?? 0).toLocaleString()}
        />

        {pushedDown.length > 0 || scanned.length > 0 ? (
          <div className={classes.predicates}>
            {pushedDown.length > 0 ? (
              <span>
                Pushed down:{" "}
                {pushedDown.map((p) => (
                  <span key={p} className={classes.predicate}>
                    {p}{" "}
                  </span>
                ))}
              </span>
            ) : null}
            {scanned.length > 0 ? (
              <span>
                Scanned by Studio:{" "}
                {scanned.map((p) => (
                  <span key={p} className={classes.predicate}>
                    {p}{" "}
                  </span>
                ))}
              </span>
            ) : null}
          </div>
        ) : null}
      </div>

      {(plan.notices ?? []).map((notice, i) => {
        const { text, tone } = noticeWords(notice);
        return (
          <Alert
            key={`${notice.kind}-${i}`}
            variant="light"
            color={tone === "warning" ? "yellow" : "gray"}
          >
            <Text size="sm">{text}</Text>
          </Alert>
        );
      })}
    </Stack>
  );
}

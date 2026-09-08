import { Anchor, Badge, Collapse, Group, Stack, Text } from "@mantine/core";
import { useState } from "react";

import type { SqlResultView } from "../api/client.ts";
import { boundWords, noticeWords } from "./notices.ts";
import classes from "./ResultMetaBar.module.css";

/**
 * One line about the result, with everything true about it behind a disclosure.
 *
 * <p>The screen used to stack up to five full-width alerts above the grid, each
 * correct and each pushing the rows further below the fold. During an incident that
 * is the worst possible arrangement: the operator scrolls past the explanations to
 * reach the data, which is exactly the text they needed to read.
 *
 * <p>So nothing is removed and nothing is softened — every bound, every notice and
 * the index's provenance statement are all still here, in full, one click away and
 * summarised in the bar itself. What changes is that the rows stay on screen.
 */
export function ResultMetaBar({
  result,
  rowCount,
  verdict,
}: {
  result: SqlResultView;
  rowCount: number;
  verdict: { text: string; tone?: "warning" | "danger" };
}) {
  const [open, setOpen] = useState(false);
  const bounds = result.boundsReached ?? [];
  const notices = result.notices ?? [];
  const fromIndex = result.plan?.source === "INDEX";
  const captured = fromIndex && result.plan?.captured === true;
  const statements = bounds.length + notices.length + (fromIndex ? 1 : 0);

  return (
    <div className={classes.bar}>
      <Group gap="xs" justify="space-between" wrap="wrap">
        <Group gap="xs" wrap="wrap">
          {/* State goes in words. Colour is redundant emphasis and appears only
              where something is actually wrong. */}
          <Text
            size="sm"
            fw={600}
            c={verdict.tone ? `var(--as-${verdict.tone})` : undefined}
          >
            {verdict.text}
          </Text>
          <Badge size="sm" variant="light" color="gray">
            {fromIndex
              ? captured
                ? "from the index — captured"
                : "from the index — sampled"
              : "from the brokers"}
          </Badge>
          {bounds.length > 0 ? (
            <Badge size="sm" variant="light" color="yellow">
              incomplete
            </Badge>
          ) : null}
        </Group>
        {statements > 0 ? (
          <Anchor
            component="button"
            type="button"
            size="xs"
            onClick={() => setOpen((was) => !was)}
            aria-expanded={open}
          >
            {open
              ? "Hide what this means"
              : `What this means (${statements} thing${statements === 1 ? "" : "s"})`}
          </Anchor>
        ) : null}
      </Group>

      <Collapse expanded={open}>
        <Stack gap={6} className={classes.detail}>
          {fromIndex ? (
            <Text size="sm">
              {captured
                ? 'These rows were captured: a divert copied every message the address routed into a queue Studio drains, so a message that was consumed immediately is still here. A message may have been consumed since it was captured — "verify on broker", on any row, asks a broker whether it is still there.'
                : "These rows are what Studio observed while sampling these queues. A message may have been consumed since it was seen, and one that arrived and left between two samples was never indexed at all."}
            </Text>
          ) : null}
          {bounds.map((bound, i) => (
            <Text key={`${bound.kind}-${i}`} size="sm" c="var(--as-warning)">
              {boundWords(bound)}
            </Text>
          ))}
          {notices.map((notice, i) => {
            const { text, tone } = noticeWords(notice);
            return (
              <Text
                key={`${notice.kind}-${i}`}
                size="sm"
                c={tone ? "var(--as-warning)" : undefined}
              >
                {text}
              </Text>
            );
          })}
          <Text size="xs" c="dimmed">
            {rowCount.toLocaleString()} row{rowCount === 1 ? "" : "s"} in view.
          </Text>
        </Stack>
      </Collapse>
    </div>
  );
}

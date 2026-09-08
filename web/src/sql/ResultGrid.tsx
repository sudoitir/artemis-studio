import { useMemo } from "react";
import { Badge, Group, Text } from "@mantine/core";

import type { SqlRowView } from "../api/client.ts";
import { absoluteLabel } from "../app/time.ts";
import { useDisplayZone } from "../app/timezone.ts";
import { VirtualTable, type GridColumn } from "../grid/VirtualTable.tsx";
import { VerifyOnBroker } from "./VerifyOnBroker.tsx";
import { rowKey } from "./useSqlTail.ts";
import classes from "./ResultGrid.module.css";

function columnsFor(clusterId: string): GridColumn<SqlRowView>[] {
  return [
    {
      id: "source",
      header: "Source",
      accessor: (r) =>
        r.source === "INDEX" ? (r.origin ?? "INDEX") : "BROKER",
      width: 110,
      // Three provenances, not two, and each in its own words. "Indexed" covers a
      // sampled row and a captured one, which make different claims: a sampled row
      // says a poll saw this message, a captured one says the address routed it.
      cell: (r) =>
        r.source !== "INDEX" ? (
          <Badge
            size="xs"
            variant="default"
            title="Read from the live broker just now"
          >
            live
          </Badge>
        ) : r.origin === "CAPTURED" ? (
          <Badge
            size="xs"
            variant="light"
            color="gray"
            title="Copied by a divert as the address routed it; it may have been consumed since"
          >
            captured
          </Badge>
        ) : (
          <Badge
            size="xs"
            variant="light"
            color="gray"
            title="Seen by a poll of this queue; a message consumed between polls was never recorded"
          >
            sampled
          </Badge>
        ),
    },
    {
      id: "node",
      header: "Node",
      accessor: (r) => r.nodeName ?? "",
      width: 150,
    },
    { id: "queue", header: "Queue", accessor: (r) => r.queueName ?? "" },
    {
      id: "messageId",
      header: "Message ID",
      accessor: (r) => r.messageId ?? "",
      width: 150,
    },
    {
      id: "timestamp",
      header: "Enqueued",
      accessor: (r) => absoluteLabel(r.timestamp),
      width: 200,
    },
    {
      id: "priority",
      header: "Prio",
      accessor: (r) => r.priority ?? 0,
      numeric: true,
      width: 70,
    },
    {
      id: "size",
      header: "Size",
      accessor: (r) => r.size ?? 0,
      numeric: true,
      width: 90,
    },
    {
      id: "body",
      header: "Body",
      accessor: (r) => r.body ?? "",
      cell: (r) => (
        <Group gap={6} wrap="nowrap">
          <Text size="xs" truncate>
            {r.body ?? (
              <Text span c="dimmed">
                (empty)
              </Text>
            )}
          </Text>
          {r.bodyTruncated ? (
            <Badge
              size="xs"
              color="yellow"
              variant="light"
              title="Cut by the management channel"
            >
              truncated
            </Badge>
          ) : null}
        </Group>
      ),
    },
    {
      id: "verify",
      header: "On broker",
      accessor: () => "",
      width: 120,
      // Only an indexed row raises the question. A live row was read from the
      // broker moments ago, so offering to re-ask would be theatre.
      cell: (r) =>
        r.source === "INDEX" ? (
          <VerifyOnBroker clusterId={clusterId} row={r} />
        ) : null,
    },
  ];
}

/**
 * The result set, in the console's existing virtualised grid — same paging,
 * sorting and node attribution as every other tabular view.
 *
 * <p>The leading column is where the row came from, not what it says: a live row
 * and an indexed row mean different things, and which one an operator is looking
 * at has to be answerable without opening it.
 */
export function ResultGrid({
  clusterId,
  rows,
  onOpen,
  emptyLabel,
  freshKeys,
  columnIds,
  onAtTopChange,
}: {
  clusterId: string;
  rows: SqlRowView[];
  onOpen: (row: SqlRowView) => void;
  emptyLabel: React.ReactNode;
  /** Keys the live tail delivered in the last few seconds. */
  freshKeys?: ReadonlySet<string>;
  /** The columns to show, in the order to show them. Defaults to all of them. */
  columnIds?: readonly string[];
  onAtTopChange?: (atTop: boolean) => void;
}) {
  const columns = useMemo(() => {
    const all = columnsFor(clusterId);
    if (!columnIds) return all;
    // Ordered by the caller's list, not by the definition order: reordering is the
    // point, and a column the caller left out is simply not built.
    return columnIds
      .map((id) => all.find((c) => c.id === id))
      .filter((c): c is GridColumn<SqlRowView> => c !== undefined);
  }, [clusterId, columnIds]);
  // The Enqueued column is an absolute timestamp, so this view follows the
  // display zone (`app/timezone.ts`).
  useDisplayZone();
  return (
    <VirtualTable
      columns={columns}
      data={rows}
      rowKey={rowKey}
      onRowClick={onOpen}
      emptyLabel={emptyLabel}
      rowClassName={(r) =>
        freshKeys?.has(rowKey(r)) ? classes.fresh : undefined
      }
      onAtTopChange={onAtTopChange}
    />
  );
}

import { useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Code,
  Group,
  Loader,
  NumberInput,
  SegmentedControl,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
} from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useParams } from "@tanstack/react-router";

import {
  useCreateIndexSubscription,
  useDeleteIndexSubscription,
  useIndexSubscriptions,
  useUpdateIndexSubscription,
  type SqlIndexSubscriptionView,
} from "../api/client.ts";
import { useCan } from "../auth/useCan.ts";
import { ConfirmByTyping } from "../shared/ConfirmByTyping.tsx";
import classes from "./IndexSubscriptions.module.css";

function bytes(value: number): string {
  if (value < 1024) return `${value} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let scaled = value / 1024;
  let unit = 0;
  while (scaled >= 1024 && unit < units.length - 1) {
    scaled /= 1024;
    unit += 1;
  }
  return `${scaled.toFixed(scaled < 10 ? 1 : 0)} ${units[unit]}`;
}

function when(iso?: string | null): string {
  if (!iso) return "—";
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? "—" : at.toLocaleString();
}

/**
 * What a capture tap creates on every live node, in the words the operator needs
 * before agreeing to it. Not documentation: this is the last screen before Studio
 * starts mutating broker routing on a schedule, so the whole blast radius is here.
 */
function CaptureBlastRadius({
  pattern,
  retentionDays,
}: {
  pattern: string;
  retentionDays: number;
}) {
  const target = pattern.trim() || "these queues";
  return (
    <Alert
      color="yellow"
      variant="light"
      title="This changes routing on every live node"
    >
      <Stack gap={6}>
        <Text size="sm">
          On each live node Studio will create a{" "}
          <strong>non-exclusive divert</strong> from {target}, a{" "}
          <strong>ring-bounded, non-durable queue</strong> to hold the copies,
          an <strong>address setting</strong> that stops the broker paging or
          blocking on Studio&apos;s account, and a{" "}
          <strong>security setting</strong> restricting that queue to
          Studio&apos;s own role. Production routing is untouched — the divert
          copies, it does not take.
        </Text>
        <Text size="sm">
          None of those objects disappears when the broker restarts. Removing
          this subscription is what removes them; nothing else will.
        </Text>
        <Text size="sm">
          Every message the address routes is stored — headers, properties and
          the body — for {retentionDays} day{retentionDays === 1 ? "" : "s"} or
          until the size bound is reached, whichever comes first, and is
          searchable by anyone who can read messages on this cluster.
        </Text>
        <Text size="sm">
          The equivalent, for an estate that deploys from{" "}
          <Code>broker.xml</Code>, is a <Code>divert</Code> onto{" "}
          <Code>artemis-studio.capture.…</Code> with{" "}
          <Code>&lt;exclusive&gt;false&lt;/exclusive&gt;</Code>, plus an{" "}
          <Code>address-setting</Code> for <Code>artemis-studio.capture.#</Code>{" "}
          with <Code>DROP</Code>, a <Code>ring-size</Code> and an{" "}
          <Code>expiry-delay</Code>.
        </Text>
      </Stack>
    </Alert>
  );
}

/** Capture state on one node, in words. Colour is redundant emphasis, never the carrier. */
function CaptureNodes({
  subscription,
}: {
  subscription: SqlIndexSubscriptionView;
}) {
  const nodes = subscription.nodes ?? [];
  if (subscription.mode !== "CAPTURE") return null;
  if (nodes.length === 0) {
    return (
      <Text size="xs" c="dimmed">
        No node has been reached yet. Capture is asserted on the next reconcile
        pass.
      </Text>
    );
  }
  return (
    <Stack gap={2}>
      {nodes.map((node) => (
        <Text
          key={node.nodeId}
          size="xs"
          c={node.state === "ACTIVE" ? undefined : "var(--as-warning)"}
        >
          {node.nodeName ?? node.nodeId}:{" "}
          {node.state === "ACTIVE"
            ? `capturing since ${when(node.capturedFrom)}`
            : node.state === "DEGRADED"
              ? "capturing, losing messages"
              : node.state === "FAILED"
                ? "not capturing"
                : "not reached yet"}
          {node.droppedEstimate
            ? ` · about ${node.droppedEstimate.toLocaleString()} missed`
            : ""}
          {node.detail ? ` — ${node.detail}` : ""}
        </Text>
      ))}
    </Stack>
  );
}

/**
 * The form that starts storing message payload.
 *
 * <p>What will be kept, and for how long, is stated on the form itself rather than
 * in documentation. An operator cannot consent to storing bodies they were never
 * told were being stored, and this is the last screen before it starts.
 */
function CreateSubscription({
  clusterId,
  canWrite,
  canCapture,
}: {
  clusterId: string;
  canWrite: boolean;
  canCapture: boolean;
}) {
  const create = useCreateIndexSubscription(clusterId);
  const [pattern, setPattern] = useState("");
  const [retentionDays, setRetentionDays] = useState(7);
  const [intervalMs, setIntervalMs] = useState(5000);
  const [mode, setMode] = useState<"SAMPLE" | "CAPTURE">("SAMPLE");
  const [filterString, setFilterString] = useState("");

  return (
    <Stack gap="xs" maw={520}>
      <TextInput
        label="Queue or pattern"
        description="Artemis wildcards: * is one level, # is many. ORDER.# captures every ORDER queue."
        placeholder="ORDER.IN"
        value={pattern}
        onChange={(e) => setPattern(e.currentTarget.value)}
        size="xs"
      />
      <SegmentedControl
        size="xs"
        value={mode}
        onChange={(v) => setMode(v as "SAMPLE" | "CAPTURE")}
        data={[
          { value: "SAMPLE", label: "Sample" },
          { value: "CAPTURE", label: "Capture everything" },
        ]}
      />
      <Text size="xs" c="dimmed">
        {mode === "SAMPLE"
          ? "Studio polls these queues and records what it saw. A message that arrives and is consumed between two polls is never recorded."
          : "Studio installs a divert-fed tap on every live node and records everything the address routed, whether or not anything consumed it. It changes the broker's routing configuration."}
      </Text>
      {mode === "CAPTURE" ? (
        <TextInput
          label="Capture filter (optional)"
          description="An Artemis filter expression. Narrows both what the broker copies and what Studio stores."
          placeholder="tenant = 'acme'"
          value={filterString}
          onChange={(e) => setFilterString(e.currentTarget.value)}
          size="xs"
        />
      ) : null}
      <Group grow>
        <NumberInput
          label="Keep for (days)"
          min={1}
          max={90}
          value={retentionDays}
          onChange={(v) => setRetentionDays(typeof v === "number" ? v : 7)}
          size="xs"
        />
        <NumberInput
          label="Read every (ms)"
          min={1000}
          step={1000}
          value={intervalMs}
          onChange={(v) => setIntervalMs(typeof v === "number" ? v : 5000)}
          size="xs"
        />
      </Group>
      {mode === "CAPTURE" ? (
        <CaptureBlastRadius pattern={pattern} retentionDays={retentionDays} />
      ) : null}
      <Alert color="yellow" variant="light" title="This stores message bodies">
        <Text size="sm">
          Studio will keep a copy of every message it observes on{" "}
          {pattern.trim() || "these queues"} — headers, application properties
          and the body — in its own database for {retentionDays} day
          {retentionDays === 1 ? "" : "s"}, and then delete it. That copy is
          searchable by anyone who can read messages on this cluster. Capture is
          sampled, so it records what was seen, not everything that passed
          through.
        </Text>
      </Alert>
      <Group>
        <Button
          size="xs"
          disabled={
            (mode === "CAPTURE" ? !canCapture : !canWrite) ||
            pattern.trim().length === 0
          }
          loading={create.isPending}
          onClick={() =>
            create.mutate(
              {
                queuePattern: pattern.trim(),
                retentionDays,
                intervalMs,
                enabled: true,
                mode,
                filterString: filterString.trim() || undefined,
              },
              {
                onSuccess: () => {
                  notifications.show({
                    message:
                      mode === "CAPTURE"
                        ? `Capturing ${pattern.trim()} — the tap is installed on the next pass`
                        : `Now sampling ${pattern.trim()}`,
                  });
                  setPattern("");
                },
                onError: (err) =>
                  notifications.show({ color: "red", message: err.message }),
              },
            )
          }
        >
          {mode === "CAPTURE" ? "Start capturing" : "Start sampling"}
        </Button>
        {(mode === "CAPTURE" ? !canCapture : !canWrite) ? (
          <Text size="xs" c="dimmed">
            {mode === "CAPTURE"
              ? "Turning capture on needs the capture write permission."
              : "Creating a subscription needs the settings write permission."}
          </Text>
        ) : null}
      </Group>
    </Stack>
  );
}

/** One subscription: what it holds, whether it is capturing, and how to be rid of it. */
function SubscriptionRow({
  clusterId,
  subscription,
  canWrite,
}: {
  clusterId: string;
  subscription: SqlIndexSubscriptionView;
  canWrite: boolean;
}) {
  const update = useUpdateIndexSubscription(clusterId);
  const remove = useDeleteIndexSubscription(clusterId);
  const [confirming, setConfirming] = useState(false);

  const id = subscription.id ?? "";
  const pattern = subscription.queuePattern ?? "";
  const held = subscription.messagesHeld ?? 0;
  const retention = subscription.retentionDays ?? 0;

  return (
    <Table.Tr>
      <Table.Td>
        <Stack gap={2}>
          <Text size="sm" fw={600}>
            {pattern}
          </Text>
          <Text size="xs" c="dimmed">
            {subscription.mode === "CAPTURE" ? "capturing" : "sampling"} since{" "}
            {when(subscription.captureFrom)}
            {subscription.createdBy
              ? ` · created by ${subscription.createdBy}`
              : ""}
            {subscription.filterString
              ? ` · filter ${subscription.filterString}`
              : ""}
          </Text>
          <CaptureNodes subscription={subscription} />
        </Stack>
      </Table.Td>
      <Table.Td>
        <Stack gap={2}>
          <Text size="sm" className={classes.numeric}>
            {held.toLocaleString()} message{held === 1 ? "" : "s"}
          </Text>
          <Text size="xs" c="dimmed" className={classes.numeric}>
            {bytes(subscription.bytesHeld ?? 0)}
            {subscription.mode === "CAPTURE" && subscription.maxBytes
              ? ` of ${bytes(subscription.maxBytes)} allowed`
              : " of payload"}
            {subscription.oldestObservedAt
              ? ` · oldest ${when(subscription.oldestObservedAt)}`
              : ""}
          </Text>
        </Stack>
      </Table.Td>
      <Table.Td>
        <Badge size="sm" variant="light" color="gray">
          {retention} day{retention === 1 ? "" : "s"}
        </Badge>
      </Table.Td>
      <Table.Td>
        <Switch
          size="xs"
          label={subscription.enabled ? "Capturing" : "Paused"}
          checked={subscription.enabled ?? false}
          disabled={!canWrite || update.isPending}
          onChange={(e) =>
            update.mutate({ id, body: { enabled: e.currentTarget.checked } })
          }
        />
      </Table.Td>
      <Table.Td>
        {confirming ? (
          <Stack gap={4}>
            {/* The blast radius, stated before the action can be armed: this
                destroys captured payload that cannot be observed again. */}
            <Text size="xs">
              This deletes the subscription and the {held.toLocaleString()}{" "}
              captured message
              {held === 1 ? "" : "s"} it holds. They cannot be recovered — a
              consumed message cannot be observed a second time.
              {subscription.mode === "CAPTURE"
                ? " It also removes the divert, the capture queue, the address setting and the security setting from every node they were installed on. Nothing else removes them."
                : ""}
            </Text>
            <ConfirmByTyping
              token={pattern}
              confirmLabel="Delete and destroy captured messages"
              loading={remove.isPending}
              onConfirm={() =>
                remove.mutate(id, {
                  onSuccess: (result) => {
                    notifications.show({
                      message: `Deleted ${pattern} — ${result.messagesDestroyed.toLocaleString()} captured messages destroyed`,
                    });
                    setConfirming(false);
                  },
                  onError: (err) =>
                    notifications.show({ color: "red", message: err.message }),
                })
              }
            />
            <Button
              size="compact-xs"
              variant="subtle"
              onClick={() => setConfirming(false)}
            >
              Cancel
            </Button>
          </Stack>
        ) : (
          <Button
            size="compact-xs"
            color="red"
            variant="light"
            disabled={!canWrite}
            onClick={() => setConfirming(true)}
          >
            Delete
          </Button>
        )}
      </Table.Td>
    </Table.Tr>
  );
}

/**
 * Index subscriptions for this cluster (ADR-0059). The index is opt-in, per queue,
 * retention-bounded and disposable, and this screen is where all four of those are
 * true or not.
 */
export function IndexSubscriptions() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const subscriptions = useIndexSubscriptions(clusterId);
  const { can, loading } = useCan();
  // While grants are still loading the control is offered: refusing before the
  // answer has arrived is a claim that was never checked.
  const canWrite = loading || can("settings:write", clusterId);
  // Capture mutates broker routing, so it is a different authority from changing how
  // often Studio polls. Offered while grants are still loading, like everything else.
  const canCapture = loading || can("capture:write", clusterId);

  if (subscriptions.isError) {
    return (
      <Alert color="red" variant="light" title={subscriptions.error.title}>
        {subscriptions.error.message}
      </Alert>
    );
  }
  if (subscriptions.isPending) {
    return <Loader size="sm" />;
  }

  const rows = subscriptions.data ?? [];

  return (
    <Stack gap="md">
      {rows.length === 0 ? (
        <Alert color="gray" variant="light" title="Nothing is being indexed">
          No queue on this cluster is captured, so the SQL Console answers every
          query from the live brokers and cannot find a message that has already
          been consumed. Index a queue below to change that — it stores message
          payload, so it is a deliberate choice rather than a default.
        </Alert>
      ) : (
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Queues</Table.Th>
              <Table.Th>Held</Table.Th>
              <Table.Th>Retention</Table.Th>
              <Table.Th>State</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((subscription) => (
              <SubscriptionRow
                key={subscription.id}
                clusterId={clusterId}
                subscription={subscription}
                canWrite={
                  subscription.mode === "CAPTURE" ? canCapture : canWrite
                }
              />
            ))}
          </Table.Tbody>
        </Table>
      )}

      <CreateSubscription
        clusterId={clusterId}
        canWrite={canWrite}
        canCapture={canCapture}
      />
    </Stack>
  );
}

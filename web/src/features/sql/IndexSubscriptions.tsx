import { useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Code,
  Collapse,
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
  usePreviewIndexSubscription,
  useUpdateIndexSubscription,
  type SqlCapturePreviewView,
  type SqlIndexSubscriptionRequest,
  type SqlIndexSubscriptionView,
} from './api.ts';
import { useCan } from "../../kernel/auth/useCan.ts";
import { ConfirmByTyping } from "../../ui/ConfirmByTyping.tsx";
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
          <Code>address-setting</Code> for{" "}
          <Code>artemis-studio.capture.&lt;instance&gt;.#</Code> with{" "}
          <Code>DROP</Code>, a <Code>ring-size</Code>, a{" "}
          <Code>max-size-bytes</Code> and an <Code>expiry-delay</Code>. The
          preview below shows the exact configuration.
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
          {node.state === "DEGRADED" && subscription.filterString
            ? " · how many were missed is unavailable — a capture filter makes the broker's routed count incomparable with what was stored"
            : node.droppedEstimate
              ? ` · about ${node.droppedEstimate.toLocaleString()} missed`
              : ""}
          {node.detail ? ` — ${node.detail}` : ""}
        </Text>
      ))}
    </Stack>
  );
}

/** A bound the server enforces, checked on blur with the same limits so a refusal is seen beside its field. */
interface Limit {
  label: string;
  description: string;
  min: number;
  max: number;
}

const LIMITS = {
  ringSize: {
    label: "Ring size (messages per node)",
    description: "How many copies each capture queue holds before the broker drops the oldest.",
    min: 100,
    max: 1_000_000,
  },
  maxMegabytes: {
    label: "Stored payload limit (MB)",
    description: "Payload this subscription may keep in Studio's database before it reports itself degraded.",
    min: 1,
    max: 1_000_000,
  },
  maxRate: {
    label: "Rate limit (messages per second)",
    description: "Capture slows to this rate; the capture queue holds the backlog up to its bound.",
    min: 1,
    max: 1_000_000,
  },
  bodyCapKilobytes: {
    label: "Body stored per message (KB)",
    description: "A longer body is stored truncated, and marked so.",
    min: 1,
    max: 16 * 1024,
  },
} satisfies Record<string, Limit>;

type BoundField = keyof typeof LIMITS;

function rangeError(limit: Limit, value: number | string): string | null {
  if (value === "") return null;
  const n = Number(value);
  return n < limit.min || n > limit.max
    ? `Must be between ${limit.min.toLocaleString()} and ${limit.max.toLocaleString()}.`
    : null;
}

/** What capture would do, from the server's dry run — the same objects it will create. */
function CapturePreview({ preview }: { preview: SqlCapturePreviewView }) {
  if (preview.refusal) {
    return (
      <Alert color="red" variant="light" role="alert" title="Capture would be refused">
        {preview.refusal}
      </Alert>
    );
  }
  const addresses = preview.addresses ?? [];
  const nodes = preview.nodes ?? [];
  return (
    <Stack gap={6} role="status" aria-live="polite">
      <Text size="sm">
        Covers {addresses.length} address{addresses.length === 1 ? "" : "es"}:{" "}
        {addresses.join(", ")}
      </Text>
      <Text size="sm">
        Installed on {nodes.length} live node{nodes.length === 1 ? "" : "s"}:{" "}
        {nodes.join(", ")}
      </Text>
      <Text size="sm">
        Each capture queue holds at most{" "}
        {(preview.ringMessages ?? 0).toLocaleString()} messages or{" "}
        {bytes(preview.ringBytes ?? 0)}, whichever is reached first. Past that
        the broker drops the oldest copy and Studio reports it as missed.
      </Text>
      <Text size="xs" fw={600}>
        Created on every listed node
      </Text>
      <Stack gap={2}>
        {(preview.brokerObjects ?? []).map((object) => (
          <Code key={object}>{object}</Code>
        ))}
      </Stack>
      {preview.brokerXml ? (
        <>
          <Text size="xs" fw={600}>
            The equivalent broker.xml
          </Text>
          <Code block>{preview.brokerXml}</Code>
        </>
      ) : null}
    </Stack>
  );
}

/**
 * The form that starts storing message payload.
 *
 * <p>What will be kept, and for how long, is stated on the form itself rather than
 * in documentation. An operator cannot consent to storing bodies they were never
 * told were being stored, and this is the last screen before it starts.
 *
 * <p>Capture is armed from its dry run: the form freezes on what was previewed and
 * the pattern is typed to confirm, so what the operator agreed to is what is created.
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
  const previewCapture = usePreviewIndexSubscription(clusterId);
  const [pattern, setPattern] = useState("");
  const [retentionDays, setRetentionDays] = useState<number | string>(7);
  const [intervalMs, setIntervalMs] = useState<number | string>(5000);
  const [mode, setMode] = useState<"SAMPLE" | "CAPTURE">("SAMPLE");
  const [filterString, setFilterString] = useState("");
  const [showBounds, setShowBounds] = useState(false);
  const [bounds, setBounds] = useState<Record<BoundField, number | string>>({
    ringSize: "",
    maxMegabytes: "",
    maxRate: "",
    bodyCapKilobytes: "",
  });
  const [errors, setErrors] = useState<Partial<Record<string, string | null>>>({});
  const [preview, setPreview] = useState<SqlCapturePreviewView | null>(null);

  const frozen = preview !== null;
  const capture = mode === "CAPTURE";
  const retention = Number(retentionDays) || 7;
  const permitted = capture ? canCapture : canWrite;
  const invalid = Object.values(errors).some(Boolean);

  const check = (field: string, error: string | null) =>
    setErrors((prev) => ({ ...prev, [field]: error }));

  const optional = (value: number | string, scale = 1) =>
    value === "" ? undefined : Number(value) * scale;

  const body: SqlIndexSubscriptionRequest = {
    queuePattern: pattern.trim(),
    retentionDays: retention,
    intervalMs: Number(intervalMs) || 5000,
    enabled: true,
    mode,
    filterString: capture ? filterString.trim() || undefined : undefined,
    ringSize: capture ? optional(bounds.ringSize) : undefined,
    maxBytes: capture ? optional(bounds.maxMegabytes, 1024 * 1024) : undefined,
    maxRate: capture ? optional(bounds.maxRate) : undefined,
    bodyCapBytes: capture ? optional(bounds.bodyCapKilobytes, 1024) : undefined,
  };

  const start = () =>
    create.mutate(body, {
      onSuccess: () => {
        notifications.show({
          message: capture
            ? `Capturing ${body.queuePattern} — the tap is installed on the next pass`
            : `Now sampling ${body.queuePattern}`,
        });
        setPattern("");
        setPreview(null);
      },
    });

  const blocked = !permitted
    ? capture
      ? "Turning capture on needs the capture write permission."
      : "Creating a subscription needs the settings write permission."
    : pattern.trim().length === 0
      ? "Enter a queue or pattern first."
      : invalid
        ? "Correct the highlighted fields first."
        : null;

  return (
    <Stack gap="xs" maw={520}>
      <TextInput
        label="Queue or pattern"
        description="Artemis wildcards: * is one level, # is many. ORDER.# captures every ORDER queue."
        placeholder="ORDER.IN"
        value={pattern}
        readOnly={frozen}
        onChange={(e) => setPattern(e.currentTarget.value)}
        size="xs"
      />
      <SegmentedControl
        size="xs"
        value={mode}
        readOnly={frozen}
        onChange={(v) => setMode(v as "SAMPLE" | "CAPTURE")}
        data={[
          { value: "SAMPLE", label: "Sample" },
          { value: "CAPTURE", label: "Capture everything" },
        ]}
      />
      <Text size="xs" c="dimmed">
        {mode === "SAMPLE"
          ? "Just sampling: Studio polls these queues and records what it saw. A message that arrives and is consumed between two polls is never recorded."
          : "Studio installs a divert-fed tap on every live node and records everything the address routed, whether or not anything consumed it. It changes the broker's routing configuration."}
      </Text>
      {capture ? (
        <TextInput
          label="Capture filter (optional)"
          description="An Artemis filter expression. Narrows both what the broker copies and what Studio stores."
          placeholder="tenant = 'acme'"
          value={filterString}
          readOnly={frozen}
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
          readOnly={frozen}
          error={errors.retentionDays}
          onChange={setRetentionDays}
          onBlur={() => check("retentionDays", rangeError({ label: "", description: "", min: 1, max: 90 }, retentionDays))}
          size="xs"
        />
        <NumberInput
          label="Read every (ms)"
          min={1000}
          max={3_600_000}
          step={1000}
          value={intervalMs}
          readOnly={frozen}
          error={errors.intervalMs}
          onChange={setIntervalMs}
          onBlur={() =>
            check("intervalMs", rangeError({ label: "", description: "", min: 1000, max: 3_600_000 }, intervalMs))
          }
          size="xs"
        />
      </Group>
      {capture ? (
        <>
          <Button
            size="compact-xs"
            variant="subtle"
            aria-expanded={showBounds}
            onClick={() => setShowBounds((open) => !open)}
          >
            {showBounds ? "Hide capture bounds" : "Capture bounds (defaults apply when left empty)"}
          </Button>
          <Collapse expanded={showBounds}>
            <Stack gap="xs">
              {(Object.keys(LIMITS) as BoundField[]).map((field) => (
                <NumberInput
                  key={field}
                  label={LIMITS[field].label}
                  description={LIMITS[field].description}
                  min={LIMITS[field].min}
                  max={LIMITS[field].max}
                  value={bounds[field]}
                  readOnly={frozen}
                  error={errors[field]}
                  onChange={(v) => setBounds((prev) => ({ ...prev, [field]: v }))}
                  onBlur={() => check(field, rangeError(LIMITS[field], bounds[field]))}
                  size="xs"
                />
              ))}
            </Stack>
          </Collapse>
          <CaptureBlastRadius pattern={pattern} retentionDays={retention} />
        </>
      ) : null}
      <Alert color="yellow" variant="light" title="This stores message bodies">
        <Text size="sm">
          Studio will keep a copy of every message it observes on{" "}
          {pattern.trim() || "these queues"} — headers, application properties
          and the body — in its own database for {retention} day
          {retention === 1 ? "" : "s"}, and then delete it. That copy is
          searchable by anyone who can read messages on this cluster.{" "}
          {capture
            ? "Capture records everything the address routed, up to its bounds; anything past them is counted as missed, never silently dropped."
            : "Sampling records what was seen, not everything that passed through."}{" "}
          Sensitive values are stored masked and credentials are never stored;
          the originals of other masked values are sealed, and only users with{" "}
          <code>message:clear</code> can see them.
        </Text>
      </Alert>

      {create.isError ? (
        <Alert color="red" variant="light" role="alert" title={create.error.title}>
          {create.error.message}
        </Alert>
      ) : null}
      {previewCapture.isError ? (
        <Alert color="red" variant="light" role="alert" title={previewCapture.error.title}>
          {previewCapture.error.message}
        </Alert>
      ) : null}

      {capture && preview ? (
        <Stack gap="xs">
          <CapturePreview preview={preview} />
          {preview.refusal ? null : (
            <ConfirmByTyping
              token={body.queuePattern ?? ""}
              confirmLabel="Start capturing"
              loading={create.isPending}
              disabled={!canCapture}
              onConfirm={start}
            />
          )}
          <Group>
            <Button size="compact-xs" variant="subtle" disabled={create.isPending} onClick={() => setPreview(null)}>
              Edit
            </Button>
          </Group>
        </Stack>
      ) : (
        <Group>
          <Button
            size="xs"
            disabled={blocked !== null}
            loading={capture ? previewCapture.isPending : create.isPending}
            onClick={() =>
              capture ? previewCapture.mutate(body, { onSuccess: setPreview }) : start()
            }
          >
            {capture ? "Preview capture" : "Start sampling"}
          </Button>
          {blocked ? (
            <Text size="xs" c="dimmed">
              {blocked}
            </Text>
          ) : null}
        </Group>
      )}
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
          {subscription.notCapturing ? (
            <Text size="xs" c="var(--as-warning)">
              Recording nothing — {subscription.notCapturing}
            </Text>
          ) : null}
          {subscription.backlogInProgress ? (
            <Text size="xs" c="dimmed">
              Still indexing the messages that were already on these queues, a
              few pages per poll; until that finishes the index is not up to date.
            </Text>
          ) : null}
          {subscription.mode !== "CAPTURE" ? (
            <Text size="xs" c="dimmed">
              Just sampling: a message consumed between two polls is never
              recorded. Capture everything to record all of them.
            </Text>
          ) : null}
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
                ? " It also removes the divert, the capture queue, the address setting and the security setting from every node that answers now; a node that is unreachable is cleaned on its next reconcile pass. Nothing else removes them."
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

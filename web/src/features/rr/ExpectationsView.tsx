import { useRef, useState } from 'react';
import { ActionIcon, Button, Checkbox, NumberInput, Stack, Switch, Text } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useServerNow } from '../../kernel/time/time.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { AddressPicker } from '../queues/index.ts';
import { CaptureHint } from '../sql/index.ts';
import { ReplyAddressesHelp, ReplyAddressesInput } from './ReplyAddressesInput.tsx';
import {
  useCreateRrExpectation,
  useDeleteRrExpectation,
  useRrDiagnostics,
  useRrExpectations,
  useUpdateRrExpectation,
  type ExpectationView,
} from './api.ts';
import { expectationColumns } from './columns.ts';
import classes from './ExpectationsView.module.css';

const ADD: ActionVerb = { verb: 'Add', past: 'Added', progressive: 'Adding' };
const ENABLE: ActionVerb = { verb: 'Enable', past: 'Enabled', progressive: 'Enabling' };
const DISABLE: ActionVerb = { verb: 'Disable', past: 'Disabled', progressive: 'Disabling' };
const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };

const DEFAULT_SAMPLES = 10;

/** A number field's value: a number, or the empty string when the operator cleared it. */
type Figure = number | string;

const rowKey = (e: ExpectationView) => e.id;

const isWhole = (value: Figure): value is number => typeof value === 'number' && Number.isInteger(value);

function deadlineProblem(deadlineMs: Figure): string | null {
  if (deadlineMs === '') return null;
  return isWhole(deadlineMs) && deadlineMs >= 1
    ? null
    : "Enter the deadline in whole milliseconds, 1 or more, or leave it empty to use each message's own.";
}

function samplesProblem(samplePerMin: Figure): string | null {
  return isWhole(samplePerMin) && samplePerMin >= 1 ? null : 'Enter how many samples to take a minute, 1 or more.';
}

/** Which request addresses are traced, and how (request-reply-tracing spec). */
export function ExpectationsView({ clusterId }: Readonly<{ clusterId: string }>) {
  const expectations = useRrExpectations(clusterId);
  // What the sampler actually did, so "tracing is on" and "tracing is working" stop
  // looking the same on this screen.
  const diagnostics = useRrDiagnostics(clusterId);
  const create = useCreateRrExpectation(clusterId);
  const update = useUpdateRrExpectation(clusterId);
  const remove = useDeleteRrExpectation(clusterId);
  // Hoisted out of the row map: one clock read per render, not one per row.
  const now = useServerNow();
  // While grants load the controls are offered; the server is the enforcement point.
  const { can, loading: grantsLoading } = useCan();
  const denied = !grantsLoading && !can('cluster:write', clusterId);

  const [requestAddress, setRequestAddress] = useState('');
  const [requestError, setRequestError] = useState<string | null>(null);
  const [replyAddresses, setReplyAddresses] = useState<string[]>([]);
  const [deadlineMs, setDeadlineMs] = useState<Figure>('');
  const [deadlineError, setDeadlineError] = useState<string | null>(null);
  const [samplePerMin, setSamplePerMin] = useState<Figure>(DEFAULT_SAMPLES);
  const [samplesError, setSamplesError] = useState<string | null>(null);
  const [capturePayload, setCapturePayload] = useState(false);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [removing, setRemoving] = useState<ExpectationView | null>(null);
  const [removeOpen, setRemoveOpen] = useState(false);
  const requestInput = useRef<HTMLInputElement>(null);
  const deadlineInput = useRef<HTMLInputElement>(null);
  const samplesInput = useRef<HTMLInputElement>(null);

  const checkRequest = () => {
    const problem = requestAddress.trim() ? null : 'Enter the request address to trace, such as orders.request.';
    setRequestError(problem);
    return problem;
  };
  const checkDeadline = () => {
    const problem = deadlineProblem(deadlineMs);
    setDeadlineError(problem);
    return problem;
  };
  const checkSamples = () => {
    const problem = samplesProblem(samplePerMin);
    setSamplesError(problem);
    return problem;
  };

  const submit = () => {
    // Every field is checked, so every message shows; focus goes to the first wrong one.
    const problems = [checkRequest(), checkDeadline(), checkSamples()];
    const first = problems.findIndex(Boolean);
    if (first >= 0) {
      [requestInput, deadlineInput, samplesInput][first].current?.focus();
      return;
    }
    const address = requestAddress.trim();
    const subject = `traced address ${address}`;
    create.mutate(
      {
        requestAddress: address,
        replyAddresses,
        correlationProperty: undefined,
        deadlineMs: isWhole(deadlineMs) ? deadlineMs : undefined,
        samplePerMin: isWhole(samplePerMin) ? samplePerMin : DEFAULT_SAMPLES,
        capturePayload,
      },
      {
        onSuccess: () => {
          setRequestAddress('');
          setReplyAddresses([]);
          setDeadlineMs('');
          setSamplePerMin(DEFAULT_SAMPLES);
          setCapturePayload(false);
          notify.succeeded({ action: ADD, subject });
        },
        onError: (error) =>
          notify.failed({
            action: ADD,
            subject,
            cause: error.message,
            next: 'Nothing was added. Check the address and try again.',
          }),
      },
    );
  };

  const toggle = (e: ExpectationView, enabled: boolean) => {
    const action = enabled ? ENABLE : DISABLE;
    const subject = `tracing of ${e.requestAddress}`;
    update.mutate(
      {
        id: e.id,
        body: {
          replyAddresses: e.replyAddresses,
          correlationProperty: e.correlationProperty ?? undefined,
          deadlineMs: e.deadlineMs ?? undefined,
          samplePerMin: e.samplePerMin,
          capturePayload: e.capturePayload,
          enabled,
        },
      },
      {
        onSuccess: () => notify.succeeded({ action, subject }),
        // Every failure is reported. A silently ignored error reads as a switch that flipped back
        // on its own, which is the least diagnosable outcome.
        onError: (error) =>
          notify.failed({
            action,
            subject,
            cause: error.message,
            next: 'The switch shows what is stored; try again.',
          }),
      },
    );
  };

  const confirmRemove = (e: ExpectationView) =>
    remove.mutate(e.id, {
      onSuccess: () => notify.succeeded({ action: REMOVE, subject: `traced address ${e.requestAddress}` }),
      onError: (error) =>
        notify.failed({
          action: REMOVE,
          subject: `traced address ${e.requestAddress}`,
          cause: error.message,
          next: 'It is still listed; reload to see its current state.',
        }),
      onSettled: () => setRemoveOpen(false),
    });

  const savingId = update.isPending ? update.variables.id : undefined;
  // Built each render: the cells carry what is gated, busy and saving right now.
  const columns = expectationColumns({
    now,
    statusOf: (id) => diagnostics.data?.expectations.find((d) => d.expectationId === id),
    enabledControl: (e) => (
      <Switch
        size="sm"
        aria-label={`Trace ${e.requestAddress}`}
        checked={e.enabled}
        disabled={denied || savingId === e.id}
        onChange={(event) => toggle(e, event.currentTarget.checked)}
      />
    ),
    removeControl: (e) => (
      <ActionIcon
        variant="subtle"
        aria-label={`Remove ${e.requestAddress}`}
        disabled={denied || (remove.isPending && remove.variables === e.id)}
        onClick={() => {
          setRemoving(e);
          setRemoveOpen(true);
        }}
      >
        <IconTrash size="1rem" aria-hidden />
      </ActionIcon>
    ),
  });

  return (
    <Section
      title="Traced addresses"
      description="Declare which request-reply addresses Studio should reconstruct flows for. Tracing is sampled — see the Latency tab for what that means for reported numbers."
    >
      {denied ? (
        <Text size="sm" role="status">
          You cannot change what is traced: that needs the <code>cluster:write</code> permission on this cluster.
        </Text>
      ) : null}

      <Section headingLevel={3} title="Add an address">
        <div className={classes.form}>
          <AddressPicker
            clusterId={clusterId}
            label="Request address"
            placeholder="orders.request"
            value={requestAddress}
            onChange={(value) => {
              setRequestAddress(value);
              setRequestError(null);
            }}
            onBlur={checkRequest}
            error={requestError}
            inputRef={requestInput}
            unknownHint="No address on this cluster has that name yet."
            w="100%"
          />
          <ReplyAddressesInput clusterId={clusterId} value={replyAddresses} onChange={setReplyAddresses} w="100%" />
          <NumberInput
            ref={deadlineInput}
            label="Deadline (ms)"
            placeholder="from message"
            allowDecimal={false}
            min={1}
            value={deadlineMs}
            error={deadlineError}
            onChange={setDeadlineMs}
            onBlur={checkDeadline}
          />
          <NumberInput
            ref={samplesInput}
            label="Samples/min"
            allowDecimal={false}
            min={1}
            value={samplePerMin}
            error={samplesError}
            onChange={setSamplePerMin}
            onBlur={checkSamples}
          />
          <div className={classes.actions}>
            <Checkbox
              label="Capture payload"
              checked={capturePayload}
              onChange={(e) => setCapturePayload(e.currentTarget.checked)}
            />
            <Button onClick={submit} loading={create.isPending} disabled={denied}>
              Add
            </Button>
          </div>
          <div className={classes.help}>
            <ReplyAddressesHelp clusterId={clusterId} value={replyAddresses} />
          </div>
        </div>
      </Section>

      <CaptureHint
        clusterId={clusterId}
        purpose="request-reply tracing"
        addresses={(expectations.data ?? [])
          .filter((e) => e.enabled)
          .flatMap((e) => [e.requestAddress, ...e.resolvedReplyAddresses])}
      />

      <Section headingLevel={3} title="Declared addresses">
        <Stack gap="sm">
          <DataTable
            variant="static"
            label="Traced addresses"
            storageKey="rr.expectations"
            columns={columns}
            data={expectations.data ?? []}
            rowKey={rowKey}
            loading={expectations.isPending}
            error={
              expectations.isError ? (
                <ErrorState error={expectations.error} onRetry={() => void expectations.refetch()} />
              ) : undefined
            }
            empty={
              <EmptyState
                kind="empty"
                title="No addresses declared yet"
                description="Traffic on this cluster is not being traced. A declared address is a request address Studio reconstructs request-reply flows for; add one with the form above."
              />
            }
          />
        </Stack>
      </Section>

      <ConfirmDialog
        opened={removeOpen}
        onClose={() => setRemoveOpen(false)}
        title="Stop tracing this address"
        tone="danger"
        typedName={removing?.requestAddress}
        pending={remove.isPending}
        confirmLabel="Stop tracing"
        consequence={
          removing ? (
            <>
              Studio stops reconstructing request-reply flows for <strong>{removing.requestAddress}</strong>. Its
              settings (reply addresses, deadline, sample rate and payload capture) are deleted and cannot be restored.
              Flows already recorded stay.
            </>
          ) : null
        }
        onConfirm={() => removing && confirmRemove(removing)}
      />
    </Section>
  );
}

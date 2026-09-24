import { useRef, useState } from 'react';
import { Alert, Button, Checkbox, Code, CopyButton, Group, Modal, Stack, Switch, Text, TextInput } from '@mantine/core';

import { useCluster } from '../clusters/index.ts';
import { useCreateDivert, useDeleteDivert, type DivertMutationView, type DivertView, type LifecycleOutcomeView } from './api.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { focusBack } from '../../kernel/actions/focusBack.ts';
import { useActionHost } from '../../kernel/actions/hostContext.ts';
import type { HostedDialogProps } from '../../kernel/actions/types.ts';
import { useDivertWriteGate } from './divertGate.ts';

const PERMISSION_LABEL = 'Create and delete diverts';

/**
 * What a divert costs a broker's configuration, stated wherever a divert is
 * created or listed.
 *
 * <p>Deliberately not "temporary". A divert created over the management API
 * survives a broker restart, along with the address and security settings that
 * come with it — measured on Artemis 2.56.0 (ADR-0065). The hazard is the
 * opposite one: the broker goes on diverting and its own configuration says
 * nothing about it, so the next deployment of that configuration silently
 * disagrees with the running broker.
 */
export const DRIFT_SENTENCE =
  'This divert stays on the broker across restarts and will not appear in the broker.xml your ' +
  'brokers deploy from, so the running broker and its configuration will disagree until you ' +
  'add it there. Nothing removes it for you — deleting it here is what takes it away.';

/** The broker.xml that closes the gap, copyable in one action. */
export function BrokerXmlRemedy({ xml }: { xml: string }) {
  return (
    <Stack gap={4}>
      <Group justify="space-between" align="center">
        <Text size="xs" fw={600} c="dimmed">
          Add this to broker.xml to make the configuration match
        </Text>
        <CopyButton value={xml}>
          {({ copied, copy }) => (
            <Button size="compact-xs" variant="subtle" onClick={copy}>
              {copied ? 'Copied' : 'Copy'}
            </Button>
          )}
        </CopyButton>
      </Group>
      <Code block>{xml}</Code>
    </Stack>
  );
}

type DivertField = 'name' | 'address' | 'forwardingAddress' | 'filter';

const FIELD_ORDER: DivertField[] = ['name', 'address', 'forwardingAddress', 'filter'];

/** The server's rules (LifecycleRequests.CreateDivertRequest), checked on blur so the message sits beside its field. */
function divertError(field: DivertField, values: Record<DivertField, string>): string | null {
  const value = values[field].trim();
  switch (field) {
    case 'name':
      if (!value) return 'A divert needs a name.';
      if (value.length > 200) return 'At most 200 characters.';
      if (/[\s,=:*?"\\]/.test(value)) return 'A divert name cannot contain whitespace or any of , = : * ? " \\';
      if (value.startsWith('artemis-studio.capture.')) return 'This namespace belongs to message capture.';
      return null;
    case 'address':
      if (!value) return 'A divert needs the address it reads from.';
      return value.length > 200 ? 'At most 200 characters.' : null;
    case 'forwardingAddress':
      if (!value) return 'A divert needs the address it forwards to.';
      if (value.length > 200) return 'At most 200 characters.';
      return value === values.address.trim() ? 'A divert cannot forward to the address it reads from.' : null;
    case 'filter':
      return value.length > 4000 ? 'At most 4000 characters.' : null;
  }
}

/** A server field error, on the field the operator can correct. */
function serverFieldFor(field: string): DivertField | null {
  if (field === 'forwardingAddressDistinct') return 'forwardingAddress';
  return (FIELD_ORDER as string[]).includes(field) ? (field as DivertField) : null;
}

/**
 * Create a divert across every live node.
 *
 * <p>The preview and the configuration remedy arrive in the same response and are
 * rendered together above the creating control, so neither can be skipped past.
 * While a preview is shown the form is read-only: what is created is exactly what
 * was previewed, and changing it means going back to edit and previewing again.
 */
export function CreateDivertAction({ clusterId }: { clusterId: string }) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const write = cluster.data?.capabilities.managementWrite;
  const gate = gateFor(can('divert:write', clusterId), PERMISSION_LABEL, write, loading || cluster.isPending);

  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Record<DivertField, string>>({
    name: '',
    address: '',
    forwardingAddress: '',
    filter: '',
  });
  const [exclusive, setExclusive] = useState(false);
  const [acknowledge, setAcknowledge] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<DivertField, string | null>>>({});
  const [preview, setPreview] = useState<DivertMutationView | null>(null);
  const [result, setResult] = useState<DivertMutationView | null>(null);
  const refs = useRef<Partial<Record<DivertField, HTMLInputElement | null>>>({});

  const create = useCreateDivert(clusterId);
  const frozen = preview !== null || result !== null;
  const body = {
    name: values.name.trim(),
    address: values.address.trim(),
    forwardingAddress: values.forwardingAddress.trim(),
    filter: values.filter.trim() || undefined,
    exclusive,
    acknowledgeCaptureShadowing: exclusive && acknowledge,
  };

  const set = (field: DivertField) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.currentTarget.value;
    setValues((prev) => ({ ...prev, [field]: value }));
  };
  const blur = (field: DivertField) => () => setErrors((prev) => ({ ...prev, [field]: divertError(field, values) }));

  const focusFirst = (found: Partial<Record<DivertField, string | null>>) => {
    const first = FIELD_ORDER.find((f) => found[f]);
    if (first) refs.current[first]?.focus();
  };

  const requestPreview = () => {
    const found = Object.fromEntries(FIELD_ORDER.map((f) => [f, divertError(f, values)]));
    setErrors(found);
    if (FIELD_ORDER.some((f) => found[f])) {
      focusFirst(found);
      return;
    }
    create.mutate(
      { body, dryRun: true },
      {
        onSuccess: setPreview,
        onError: (error) => {
          const mapped: Partial<Record<DivertField, string>> = {};
          for (const fe of error.fieldErrors) {
            const field = serverFieldFor(fe.field);
            if (field) mapped[field] = fe.message;
          }
          setErrors(mapped);
          focusFirst(mapped);
        },
      },
    );
  };

  const close = () => {
    // Closing mid-request would hide the outcome of a change that is still being made.
    if (create.isPending) return;
    setOpen(false);
    setPreview(null);
    setResult(null);
    setErrors({});
    create.reset();
  };

  const refused = preview?.outcome.nodes.filter((n) => n.status === 'FAILED') ?? [];

  const input = (field: DivertField, label: string, description?: string) => (
    <TextInput
      ref={(el) => {
        refs.current[field] = el;
      }}
      label={label}
      description={description}
      value={values[field]}
      onChange={set(field)}
      onBlur={blur(field)}
      error={errors[field]}
      readOnly={frozen}
      size="xs"
    />
  );

  return (
    <>
      <CapabilityGate verdict={gate}>
        <Button size="xs" variant="light" disabled={gate.kind === 'blocked'} onClick={() => setOpen(true)}>
          Create divert
        </Button>
      </CapabilityGate>

      <Modal opened={open} onClose={close} title="Create a divert" size="lg">
        <Stack gap="sm">
          {input('name', 'Name', 'Unique on each node.')}
          {input('address', 'Divert messages from')}
          {input('forwardingAddress', 'Divert messages to')}
          {input('filter', 'Filter', 'Optional. Only messages matching it are diverted.')}
          <Switch
            size="xs"
            checked={exclusive}
            disabled={frozen}
            onChange={(e) => setExclusive(e.currentTarget.checked)}
            label="Exclusive — take the message instead of copying it"
            description={
              exclusive
                ? 'Traffic on this address stops reaching its current destinations. Artemis evaluates exclusive diverts before non-exclusive ones, so this also runs ahead of any copying divert on the same address.'
                : 'Traffic is copied. Its current destinations keep receiving it.'
            }
          />
          {exclusive ? (
            <Checkbox
              size="xs"
              checked={acknowledge}
              disabled={frozen}
              onChange={(e) => setAcknowledge(e.currentTarget.checked)}
              label="Create it even if this address is being captured"
              description="Capture of the address would then record nothing while this divert exists. Needed only when the preview says the address is captured."
            />
          ) : null}

          {create.isError && create.error.fieldErrors.length === 0 ? (
            <Alert color="red" variant="light" title={create.error.title}>
              {create.error.message}
            </Alert>
          ) : null}

          {result ? (
            <Stack gap="sm">
              <NodeOutcomeSummary outcome={result.outcome} />
              <Alert variant="light" color="yellow" title="Your broker configuration does not know about this">
                <Stack gap="sm">
                  <Text size="xs">{DRIFT_SENTENCE}</Text>
                  <BrokerXmlRemedy xml={result.brokerXml} />
                </Stack>
              </Alert>
            </Stack>
          ) : preview ? (
            <Stack gap="sm">
              <NodeOutcomeSummary outcome={preview.outcome} />
              {refused.length > 0 ? (
                <Alert color="red" variant="light" title="This divert would be refused">
                  {refused.length === preview.outcome.nodes.length
                    ? 'Every node refuses it, for the reason under each node above. Edit it and preview again.'
                    : 'Some nodes refuse it, for the reason under each node above. Creating it anyway applies it only where it is not refused.'}
                </Alert>
              ) : null}
              <Alert variant="light" color="yellow" title="Before you create this">
                <Stack gap="sm">
                  <Text size="xs">{DRIFT_SENTENCE}</Text>
                  <BrokerXmlRemedy xml={preview.brokerXml} />
                </Stack>
              </Alert>
            </Stack>
          ) : null}

          <Group justify="flex-end">
            {result ? (
              <Button size="xs" onClick={close}>
                Done
              </Button>
            ) : preview ? (
              <>
                <Button size="xs" variant="subtle" disabled={create.isPending} onClick={() => setPreview(null)}>
                  Edit
                </Button>
                {refused.length === preview.outcome.nodes.length ? null : (
                  <Button
                    size="xs"
                    loading={create.isPending}
                    onClick={() => create.mutate({ body, dryRun: false }, { onSuccess: setResult })}
                  >
                    Create on every live node
                  </Button>
                )}
              </>
            ) : (
              <Button size="xs" loading={create.isPending} onClick={requestPreview}>
                Preview
              </Button>
            )}
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

/**
 * Delete one divert, across every live node.
 *
 * <p>A capture divert is not deletable here. Reconciliation would reinstate it on
 * its next pass, so the deletion would appear to succeed and then silently undo
 * itself; the operator is sent to the capture subscription that owns it instead.
 */
export function DeleteDivertAction({ clusterId, divert }: { clusterId: string; divert: DivertView }) {
  const gate = useDivertWriteGate(clusterId);
  const host = useActionHost();

  if (divert.owner === 'MESSAGE_CAPTURE') {
    return (
      <Text size="xs" c="dimmed">
        Owned by message capture
      </Text>
    );
  }

  return (
    <CapabilityGate verdict={gate}>
      <Button
        size="compact-xs"
        variant="subtle"
        color="red"
        disabled={gate.kind === 'blocked'}
        aria-label={`Delete divert ${divert.name}`}
        // Hosted outside the grid (ADR-0107), so a delete that removes this row keeps its outcome.
        onClick={(e) =>
          host.open(DeleteDivertDialog, { clusterId, divert }, { restoreFocus: focusBack(e.currentTarget) })
        }
      >
        Delete
      </Button>
    </CapabilityGate>
  );
}

/**
 * The delete itself: it opens on the preview of what each node would do, and is armed by typing the
 * divert's name.
 */
export function DeleteDivertDialog({
  clusterId,
  divert,
  opened,
  onClose,
}: HostedDialogProps & { clusterId: string; divert: DivertView }) {
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  const [previewFailed, setPreviewFailed] = useState<string | null>(null);
  const remove = useDeleteDivert(clusterId, divert.name);

  const takePreview = () => {
    setPreview(null);
    setPreviewFailed(null);
    remove.mutate({ dryRun: true }, { onSuccess: setPreview, onError: (e) => setPreviewFailed(e.message) });
  };

  const close = () => {
    if (remove.isPending && preview) return;
    setPreview(null);
    setResult(null);
    setPreviewFailed(null);
    remove.reset();
    onClose();
  };

  return (
    <Modal
      opened={opened}
      onClose={close}
      onEnterTransitionEnd={takePreview}
      title={`Delete divert "${divert.name}"`}
      size="lg"
    >
      <Stack gap="sm">
        <Text size="sm">
          {divert.exclusive
            ? `Messages on ${divert.address} stop going to ${divert.forwardingAddress} and resume reaching their original destinations.`
            : `${divert.forwardingAddress} stops receiving a copy of the messages on ${divert.address}. Traffic on ${divert.address} itself is unaffected.`}
        </Text>

        <div aria-live="polite">
          {remove.isPending && !preview && !result ? (
            <Text size="sm" c="dimmed">
              Asking each node what the delete would do…
            </Text>
          ) : null}
          {previewFailed ? (
            <Alert color="yellow" variant="light" title="The preview could not be taken" role="alert">
              {previewFailed} Nothing was deleted. Close this and try again once the nodes answer.
            </Alert>
          ) : null}
          {remove.isError && !previewFailed ? (
            <Alert color="red" variant="light" title={remove.error.title} role="alert">
              {remove.error.message}
            </Alert>
          ) : null}
          {result ? <NodeOutcomeSummary outcome={result} /> : preview ? <NodeOutcomeSummary outcome={preview} /> : null}
        </div>

        {preview && !result ? (
          <ConfirmByTyping
            token={divert.name}
            confirmLabel="Delete on every live node"
            loading={remove.isPending}
            onConfirm={() => remove.mutate({ dryRun: false }, { onSuccess: setResult })}
          />
        ) : null}

        <Group justify="flex-end">
          <Button size="xs" variant="subtle" onClick={close}>
            {result ? 'Done' : 'Cancel'}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

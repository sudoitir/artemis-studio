import { useState } from 'react';
import { Alert, Button, Code, CopyButton, Group, Modal, Stack, Switch, Text, TextInput } from '@mantine/core';

import {
  useCluster,
  useCreateDivert,
  useDeleteDivert,
  type DivertMutationView,
  type DivertView,
  type LifecycleOutcomeView,
} from '../api/client.ts';
import { useCan } from '../auth/useCan.ts';
import { CapabilityGate } from '../shared/CapabilityGate.tsx';
import { gateFor } from '../shared/capabilityGate.ts';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';
import { NodeOutcomeSummary } from '../shared/NodeOutcomeSummary.tsx';

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

/**
 * Create a divert across every live node.
 *
 * <p>The preview and the configuration remedy arrive in the same response and are
 * rendered together above the creating control, so neither can be skipped past.
 */
export function CreateDivertAction({ clusterId }: { clusterId: string }) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const write = cluster.data?.capabilities.managementWrite;
  const gate = gateFor(can('divert:write', clusterId), PERMISSION_LABEL, write, loading || cluster.isPending);

  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [address, setAddress] = useState('');
  const [forwardingAddress, setForwardingAddress] = useState('');
  const [filter, setFilter] = useState('');
  const [exclusive, setExclusive] = useState(false);
  const [preview, setPreview] = useState<DivertMutationView | null>(null);
  const [result, setResult] = useState<DivertMutationView | null>(null);

  const create = useCreateDivert(clusterId);
  const body = { name, address, forwardingAddress, filter: filter || undefined, exclusive };
  const complete = name.trim() !== '' && address.trim() !== '' && forwardingAddress.trim() !== '';

  const close = () => {
    setOpen(false);
    setPreview(null);
    setResult(null);
    create.reset();
  };

  return (
    <>
      <CapabilityGate verdict={gate}>
        <Button size="xs" variant="light" disabled={gate.kind === 'blocked'} onClick={() => setOpen(true)}>
          Create divert
        </Button>
      </CapabilityGate>

      <Modal opened={open} onClose={close} title="Create a divert" size="lg">
        <Stack gap="sm">
          <TextInput
            label="Name"
            description="Unique on each node."
            value={name}
            onChange={(e) => setName(e.currentTarget.value)}
            size="xs"
          />
          <TextInput
            label="Divert messages from"
            value={address}
            onChange={(e) => setAddress(e.currentTarget.value)}
            size="xs"
          />
          <TextInput
            label="Divert messages to"
            value={forwardingAddress}
            onChange={(e) => setForwardingAddress(e.currentTarget.value)}
            size="xs"
          />
          <TextInput
            label="Filter"
            description="Optional. Only messages matching it are diverted."
            value={filter}
            onChange={(e) => setFilter(e.currentTarget.value)}
            size="xs"
          />
          <Switch
            size="xs"
            checked={exclusive}
            onChange={(e) => setExclusive(e.currentTarget.checked)}
            label="Exclusive — take the message instead of copying it"
            description={
              exclusive
                ? 'Traffic on this address stops reaching its current destinations. Artemis evaluates exclusive diverts before non-exclusive ones, so this also runs ahead of any copying divert on the same address.'
                : 'Traffic is copied. Its current destinations keep receiving it.'
            }
          />

          {create.isError ? (
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
                <Button size="xs" variant="subtle" onClick={() => setPreview(null)}>
                  Back
                </Button>
                <Button
                  size="xs"
                  loading={create.isPending}
                  onClick={() => create.mutate({ body, dryRun: false }, { onSuccess: setResult })}
                >
                  Create on every live node
                </Button>
              </>
            ) : (
              <Button
                size="xs"
                loading={create.isPending}
                disabled={!complete}
                onClick={() => create.mutate({ body, dryRun: true }, { onSuccess: setPreview })}
              >
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
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const write = cluster.data?.capabilities.managementWrite;
  const gate = gateFor(can('divert:write', clusterId), PERMISSION_LABEL, write, loading || cluster.isPending);

  const [open, setOpen] = useState(false);
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  const remove = useDeleteDivert(clusterId, divert.name);

  if (divert.owner === 'MESSAGE_CAPTURE') {
    return (
      <Text size="xs" c="dimmed">
        Owned by message capture
      </Text>
    );
  }

  const close = () => {
    setOpen(false);
    setPreview(null);
    setResult(null);
    remove.reset();
  };

  return (
    <>
      <CapabilityGate verdict={gate}>
        <Button
          size="compact-xs"
          variant="subtle"
          color="red"
          disabled={gate.kind === 'blocked'}
          onClick={() => {
            setOpen(true);
            remove.mutate({ dryRun: true }, { onSuccess: setPreview });
          }}
        >
          Delete
        </Button>
      </CapabilityGate>

      <Modal opened={open} onClose={close} title={`Delete divert "${divert.name}"`} size="lg">
        <Stack gap="sm">
          <Text size="sm">
            {divert.exclusive
              ? `Messages on ${divert.address} stop going to ${divert.forwardingAddress} and resume reaching their original destinations.`
              : `${divert.forwardingAddress} stops receiving a copy of the messages on ${divert.address}. Traffic on ${divert.address} itself is unaffected.`}
          </Text>

          {remove.isError ? (
            <Alert color="red" variant="light" title={remove.error.title}>
              {remove.error.message}
            </Alert>
          ) : null}

          {result ? (
            <NodeOutcomeSummary outcome={result} />
          ) : preview ? (
            <>
              <NodeOutcomeSummary outcome={preview} />
              <ConfirmByTyping
                token={divert.name}
                confirmLabel="Delete on every live node"
                loading={remove.isPending}
                onConfirm={() => remove.mutate({ dryRun: false }, { onSuccess: setResult })}
              />
            </>
          ) : null}

          <Group justify="flex-end">
            <Button size="xs" variant="subtle" onClick={close}>
              {result ? 'Done' : 'Cancel'}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

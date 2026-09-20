import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Group,
  NumberInput,
  PasswordInput,
  Select,
  Stack,
  Switch,
  TagsInput,
  Text,
  TextInput,
} from '@mantine/core';

import {
  useBridgeCredentials,
  useConfigConnectors,
  useSetBridgeCredential,
  type ConfigBridgeView,
  type ConfigDeclarationView,
} from '../api.ts';
import { keyTaken, removeItem, upsertBridge } from '../document.ts';
import { EditorDrawer } from '../EditorDrawer.tsx';
import { useSaveDocument } from '../useSaveDocument.ts';
import { TransformerFields, type TransformerValue } from './TransformerFields.tsx';

/** The name a bridge can carry: the broker puts it in a JMX object name. */
const MANAGEMENT_NAME = /^[^\s,=:*?"\\]*$/;

/** `ComponentConfigurationRoutingType` as a bridge accepts it; the broker's default is PASS. */
const ROUTING_TYPES = ['STRIP', 'PASS', 'ANYCAST', 'MULTICAST', 'OFFSET'];

interface Errors {
  name?: string;
  queueName?: string;
  forwardingAddress?: string;
  connectors?: string;
}

/** What a drag on the canvas prefills a new bridge with. */
export interface BridgePrefill {
  queueName?: string;
  forwardingAddress?: string;
}

function blank(v: string | null | undefined): boolean {
  return !v || v.trim() === '';
}

/** A number field's value on the wire: empty means "not declared — keep the broker's default". */
function num(v: string | number): number | null {
  if (v === '' || v === null || v === undefined) return null;
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

/**
 * Edit one declared bridge (ADR-0091). Identity first — what it reads, what it
 * forwards to, and how it gets there — with the rest behind the disclosure the
 * divert editor already uses, because a bridge has over twenty fields and three
 * of them are the ones an operator came for.
 *
 * <p>Artemis has no in-place update, so a changed bridge is applied as a removal
 * and a creation. The editor says so here, in the same words the plan uses, so
 * the High hazard there is not the first time an operator reads it.
 *
 * <p>The credential is a reference into Studio's vault (ADR-0092). The password
 * is sealed by its own request and never enters the declaration, a revision, a
 * diff, an audit parameter or the exported XML.
 */
export function BridgeEditor({
  declaration,
  item,
  prefill,
  opened,
  onClose,
}: {
  declaration: ConfigDeclarationView;
  item: ConfigBridgeView | null;
  prefill?: BridgePrefill;
  opened: boolean;
  onClose: () => void;
}) {
  const [name, setName] = useState('');
  const [queueName, setQueueName] = useState('');
  const [forwardingAddress, setForwardingAddress] = useState('');
  const [connectors, setConnectors] = useState<string[]>([]);
  const [discoveryGroupName, setDiscoveryGroupName] = useState('');
  const [filter, setFilter] = useState('');
  const [transformer, setTransformer] = useState<TransformerValue>({ className: '', properties: {} });
  const [routingType, setRoutingType] = useState('');
  const [ha, setHa] = useState(false);
  const [useDuplicateDetection, setUseDuplicateDetection] = useState(false);
  const [concurrency, setConcurrency] = useState<string | number>('');
  const [retryInterval, setRetryInterval] = useState<string | number>('');
  const [retryIntervalMultiplier, setRetryIntervalMultiplier] = useState<string | number>('');
  const [maxRetryInterval, setMaxRetryInterval] = useState<string | number>('');
  const [initialConnectAttempts, setInitialConnectAttempts] = useState<string | number>('');
  const [reconnectAttempts, setReconnectAttempts] = useState<string | number>('');
  const [confirmationWindowSize, setConfirmationWindowSize] = useState<string | number>('');
  const [producerWindowSize, setProducerWindowSize] = useState<string | number>('');
  const [minLargeMessageSize, setMinLargeMessageSize] = useState<string | number>('');
  const [checkPeriod, setCheckPeriod] = useState<string | number>('');
  const [connectionTtl, setConnectionTtl] = useState<string | number>('');
  const [clientId, setClientId] = useState('');
  const [credentialRef, setCredentialRef] = useState('');
  const [newCredentialUser, setNewCredentialUser] = useState('');
  const [newCredentialPassword, setNewCredentialPassword] = useState('');

  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);
  const queueRef = useRef<HTMLInputElement>(null);
  const forwardRef = useRef<HTMLInputElement>(null);
  const connectorRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!opened) return;
    setName(item?.name ?? '');
    setQueueName(item?.queueName ?? prefill?.queueName ?? '');
    setForwardingAddress(item?.forwardingAddress ?? prefill?.forwardingAddress ?? '');
    setConnectors(item?.staticConnectors ?? []);
    setDiscoveryGroupName(item?.discoveryGroupName ?? '');
    setFilter(item?.filter ?? '');
    setTransformer({
      className: item?.transformer?.className ?? '',
      properties: { ...(item?.transformer?.properties ?? {}) },
    });
    setRoutingType(item?.routingType ?? '');
    setHa(item?.ha ?? false);
    setUseDuplicateDetection(item?.useDuplicateDetection ?? false);
    setConcurrency(item?.concurrency ?? '');
    setRetryInterval(item?.retryInterval ?? '');
    setRetryIntervalMultiplier(item?.retryIntervalMultiplier ?? '');
    setMaxRetryInterval(item?.maxRetryInterval ?? '');
    setInitialConnectAttempts(item?.initialConnectAttempts ?? '');
    setReconnectAttempts(item?.reconnectAttempts ?? '');
    setConfirmationWindowSize(item?.confirmationWindowSize ?? '');
    setProducerWindowSize(item?.producerWindowSize ?? '');
    setMinLargeMessageSize(item?.minLargeMessageSize ?? '');
    setCheckPeriod(item?.checkPeriod ?? '');
    setConnectionTtl(item?.connectionTtl ?? '');
    setClientId(item?.clientId ?? '');
    setCredentialRef(item?.credentialRef ?? '');
    setNewCredentialUser('');
    setNewCredentialPassword('');
    setTouched({});
    setSubmitted(false);
    setAdvanced(false);
  }, [opened, item, prefill]);

  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);
  const nodeConnectors = useConfigConnectors(declaration.clusterId, opened);
  const credentials = useBridgeCredentials(declaration.clusterId, opened);
  const storeCredential = useSetBridgeCredential(declaration.clusterId);

  /** Every connector name any node reported, and whether any node could be read at all. */
  const offered = useMemo(() => {
    const rows = nodeConnectors.data ?? [];
    const known = rows.filter((r) => r.known);
    const names = [...new Set(known.flatMap((r) => r.names))].sort();
    return {
      names,
      /** Null while nothing is known yet — not the same as "there are none" (ADR-0049 D5). */
      unknownReason:
        rows.length === 0
          ? nodeConnectors.isPending
            ? 'Studio has not read the nodes’ connector names yet.'
            : 'Studio has no node to read connector names from.'
          : known.length === 0
            ? `Studio could not read any node's connector names: ${rows[0].reason ?? 'the read was refused'}.`
            : null,
    };
  }, [nodeConnectors.data, nodeConnectors.isPending]);

  const validate = (): Errors => {
    const errors: Errors = {};
    const n = name.trim();
    if (!n) errors.name = 'A bridge name is required.';
    else if (keyTaken(declaration.document.bridges, (i) => i.name, n, item?.name)) {
      errors.name = `"${n}" is already declared. Edit that bridge instead.`;
    } else if (!MANAGEMENT_NAME.test(n)) {
      errors.name =
        'A bridge’s name cannot contain whitespace or any of , = : * ? " \\ — the broker puts it in an object name.';
    }
    if (!queueName.trim()) errors.queueName = 'A bridge needs the queue it reads from.';
    if (!forwardingAddress.trim()) errors.forwardingAddress = 'A bridge needs the address it forwards to.';
    const hasConnectors = connectors.length > 0;
    const hasDiscovery = !blank(discoveryGroupName);
    if (hasConnectors && hasDiscovery) {
      errors.connectors =
        'A bridge uses either static connectors or a discovery group, never both; the broker accepts only one. Remove one of them.';
    } else if (!hasConnectors && !hasDiscovery) {
      errors.connectors =
        'A bridge needs somewhere to connect: name at least one static connector, or a discovery group.';
    }
    return errors;
  };
  const errors = validate();
  const errorFor = (field: keyof Errors) => (touched[field] || submitted ? errors[field] : undefined);

  const submit = () => {
    setSubmitted(true);
    if (errors.name) return nameRef.current?.focus();
    if (errors.queueName) return queueRef.current?.focus();
    if (errors.forwardingAddress) return forwardRef.current?.focus();
    if (errors.connectors) {
      setAdvanced(true);
      return connectorRef.current?.focus();
    }
    const next: ConfigBridgeView = {
      name: name.trim(),
      queueName: queueName.trim(),
      forwardingAddress: forwardingAddress.trim(),
      filter: filter.trim() || null,
      transformer: transformer.className.trim()
        ? { className: transformer.className.trim(), properties: transformer.properties }
        : undefined,
      staticConnectors: connectors,
      discoveryGroupName: discoveryGroupName.trim() || null,
      ha,
      useDuplicateDetection,
      retryInterval: num(retryInterval),
      retryIntervalMultiplier: num(retryIntervalMultiplier),
      maxRetryInterval: num(maxRetryInterval),
      initialConnectAttempts: num(initialConnectAttempts),
      reconnectAttempts: num(reconnectAttempts),
      confirmationWindowSize: num(confirmationWindowSize),
      producerWindowSize: num(producerWindowSize),
      minLargeMessageSize: num(minLargeMessageSize),
      checkPeriod: num(checkPeriod),
      connectionTtl: num(connectionTtl),
      routingType: (routingType || null) as ConfigBridgeView['routingType'],
      concurrency: num(concurrency),
      clientId: clientId.trim() || null,
      credentialRef: credentialRef.trim() || null,
    };
    save(upsertBridge(declaration.document, next, item?.name), `${item ? 'Edited' : 'Added'} bridge ${next.name}`);
  };

  const remove = () => save(removeItem(declaration.document, 'bridges', item!.name), `Removed bridge ${item!.name}`);

  const credentialOptions = [
    { value: '', label: 'none — the target broker does not require a login' },
    ...(credentials.data ?? []).map((c) => ({
      value: c.ref,
      label: c.username ? `${c.ref} — as ${c.username}` : c.ref,
    })),
  ];

  return (
    <EditorDrawer
      opened={opened}
      onClose={() => {
        reset();
        onClose();
      }}
      title={item ? `Bridge ${item.name}` : 'New bridge'}
      error={error}
      submitting={isPending}
      submitLabel={`Save as revision ${declaration.revision + 1}`}
      onSubmit={submit}
      hint={submitted && Object.keys(errors).length > 0 ? 'Fix the fields above to continue.' : undefined}
      secondary={
        item ? (
          <Button variant="subtle" color="red" size="xs" onClick={remove} loading={isPending}>
            Remove from declaration
          </Button>
        ) : null
      }
    >
      <TextInput
        ref={nameRef}
        label="Name"
        value={name}
        onChange={(e) => setName(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, name: true }))}
        error={errorFor('name')}
        required
      />
      <TextInput
        ref={queueRef}
        label="From queue"
        description="The queue on this cluster whose messages are forwarded. It must exist on every node the bridge is applied to."
        value={queueName}
        onChange={(e) => setQueueName(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, queueName: true }))}
        error={errorFor('queueName')}
        required
      />
      <TextInput
        ref={forwardRef}
        label="To address"
        description="The address on the other broker. Studio does not read that broker from here, so it cannot check the address exists."
        value={forwardingAddress}
        onChange={(e) => setForwardingAddress(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, forwardingAddress: true }))}
        error={errorFor('forwardingAddress')}
        required
      />
      <TagsInput
        ref={connectorRef}
        label="Static connectors"
        description={
          offered.unknownReason
            ? `${offered.unknownReason} Type the connector name from broker.xml; it is not checked here.`
            : 'The connector names from this cluster’s broker.xml. Type one that is not offered if the nodes disagree.'
        }
        data={offered.names}
        value={connectors}
        onChange={(v) => setConnectors(v)}
        onBlur={() => setTouched((t) => ({ ...t, connectors: true }))}
        error={errorFor('connectors')}
      />
      {item ? (
        <Text size="xs" c="dimmed">
          The broker cannot change a bridge in place, so a changed bridge is applied as a removal and a creation, in
          that order. Nothing is forwarded between the two and {queueName || 'its source queue'} accumulates. The plan
          lists it as a High hazard.
        </Text>
      ) : null}

      <div>
        <Button variant="subtle" size="xs" px={0} onClick={() => setAdvanced((a) => !a)} aria-expanded={advanced}>
          {advanced ? 'Hide advanced configuration' : 'Advanced configuration'}
        </Button>
        <Collapse expanded={advanced}>
          <Stack gap="sm" mt="sm">
            <TextInput
              label="Discovery group"
              description="Instead of static connectors, not as well as them. The broker accepts one or the other."
              value={discoveryGroupName}
              onChange={(e) => setDiscoveryGroupName(e.currentTarget.value)}
              onBlur={() => setTouched((t) => ({ ...t, connectors: true }))}
            />
            <TextInput
              label="Filter"
              description="A selector; only matching messages are forwarded. Empty forwards all."
              value={filter}
              onChange={(e) => setFilter(e.currentTarget.value)}
            />
            <Select
              label="Routing type"
              description="How a forwarded message is routed on the other broker. Not declared keeps the broker's default (PASS)."
              data={[{ value: '', label: 'not declared' }, ...ROUTING_TYPES.map((v) => ({ value: v, label: v }))]}
              value={routingType}
              onChange={(v) => setRoutingType(v ?? '')}
              allowDeselect={false}
            />
            <TransformerFields value={transformer} onChange={setTransformer} what="forwarded" />

            <Select
              label="Credential"
              description="Held in Studio's vault. The declaration carries only this reference — never a password, here, in a revision, in a difference between revisions, in an audit record or in exported XML."
              data={credentialOptions}
              value={credentialRef}
              onChange={(v) => setCredentialRef(v ?? '')}
              allowDeselect={false}
            />
            <Group align="flex-end" gap="xs">
              <TextInput
                label="Store a credential as"
                description="A name for it. Storing does not save the declaration; choose it above afterwards."
                size="xs"
                value={credentialRef}
                onChange={(e) => setCredentialRef(e.currentTarget.value)}
                style={{ flex: 1 }}
              />
              <TextInput
                label="User"
                size="xs"
                value={newCredentialUser}
                onChange={(e) => setNewCredentialUser(e.currentTarget.value)}
                style={{ flex: 1 }}
              />
              <PasswordInput
                label="Password"
                size="xs"
                value={newCredentialPassword}
                onChange={(e) => setNewCredentialPassword(e.currentTarget.value)}
                style={{ flex: 1 }}
              />
              <Button
                variant="default"
                size="xs"
                loading={storeCredential.isPending}
                disabled={!credentialRef.trim() || !newCredentialPassword}
                onClick={() =>
                  storeCredential.mutate(
                    {
                      ref: credentialRef.trim(),
                      body: { username: newCredentialUser.trim() || null, password: newCredentialPassword },
                    },
                    { onSuccess: () => setNewCredentialPassword('') },
                  )
                }
              >
                Store credential
              </Button>
            </Group>
            {storeCredential.isError ? (
              <Alert color="red" variant="light" title={storeCredential.error.title} role="alert">
                {storeCredential.error.message}
              </Alert>
            ) : null}

            <Switch
              label="Highly available"
              description="Reconnect to the target's backup when the primary fails over."
              checked={ha}
              onChange={(e) => setHa(e.currentTarget.checked)}
            />
            <Switch
              label="Use duplicate detection"
              description="Adds a duplicate id so the target broker drops a message the bridge sends twice after a reconnect."
              checked={useDuplicateDetection}
              onChange={(e) => setUseDuplicateDetection(e.currentTarget.checked)}
            />
            <Group grow align="flex-start">
              <NumberInput
                label="Concurrency"
                description="Workers. Above one, the broker deploys the bridge once per worker."
                min={1}
                value={concurrency}
                onChange={setConcurrency}
              />
              <NumberInput label="Retry interval (ms)" min={1} value={retryInterval} onChange={setRetryInterval} />
              <NumberInput
                label="Retry interval multiplier"
                min={0}
                step={0.1}
                value={retryIntervalMultiplier}
                onChange={setRetryIntervalMultiplier}
              />
            </Group>
            <Group grow align="flex-start">
              <NumberInput label="Max retry interval (ms)" min={1} value={maxRetryInterval} onChange={setMaxRetryInterval} />
              <NumberInput
                label="Initial connect attempts"
                description="-1 retries forever."
                min={-1}
                value={initialConnectAttempts}
                onChange={setInitialConnectAttempts}
              />
              <NumberInput
                label="Reconnect attempts"
                description="-1 retries forever."
                min={-1}
                value={reconnectAttempts}
                onChange={setReconnectAttempts}
              />
            </Group>
            <Group grow align="flex-start">
              <NumberInput
                label="Confirmation window size (bytes)"
                description="-1 disables it."
                min={-1}
                value={confirmationWindowSize}
                onChange={setConfirmationWindowSize}
              />
              <NumberInput
                label="Producer window size (bytes)"
                description="-1 is unlimited."
                min={-1}
                value={producerWindowSize}
                onChange={setProducerWindowSize}
              />
              <NumberInput
                label="Min large message size (bytes)"
                min={1}
                value={minLargeMessageSize}
                onChange={setMinLargeMessageSize}
              />
            </Group>
            <Group grow align="flex-start">
              <NumberInput label="Check period (ms)" min={1} value={checkPeriod} onChange={setCheckPeriod} />
              <NumberInput
                label="Connection TTL (ms)"
                description="-1 never times out."
                min={-1}
                value={connectionTtl}
                onChange={setConnectionTtl}
              />
              <TextInput label="Client id" value={clientId} onChange={(e) => setClientId(e.currentTarget.value)} />
            </Group>
            <Text size="xs" c="dimmed">
              The broker reports back only thirteen of a bridge’s fields, so an apply verifies those and says nothing
              about the rest. The window sizes, the large-message size, the check period, the connection TTL, the
              routing type, the concurrency, the client id, the initial connect attempts and the credential are not
              among them (ADR-0091).
            </Text>
          </Stack>
        </Collapse>
      </div>
    </EditorDrawer>
  );
}

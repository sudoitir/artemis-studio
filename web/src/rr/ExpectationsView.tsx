import { useState } from 'react';
import {
  ActionIcon,
  Button,
  Checkbox,
  Group,
  NumberInput,
  Stack,
  Switch,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { AddressPicker } from '../queues/AddressPicker.tsx';
import { ReplyAddressesInput } from './ReplyAddressesInput.tsx';
import {
  useCreateRrExpectation,
  useDeleteRrExpectation,
  useRrExpectations,
  useUpdateRrExpectation,
  type ExpectationView,
} from '../api/client.ts';

/**
 * One expectation's declared reply addresses, and what they resolve to right now.
 *
 * The declaration and the resolution are both shown because they answer different
 * questions: the patterns say what the operator meant, the resolved set says what is
 * actually being browsed this minute. A pattern matching nothing yet is normal — the
 * responder has not started — so it reads as a state, not an error.
 */
function ReplyAddressesCell({ expectation: e }: { expectation: ExpectationView }) {
  if (e.replyAddresses.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        temporary queues
      </Text>
    );
  }

  const resolved = e.resolvedReplyAddresses;
  const patterns = e.replyAddresses.filter((a) => a.includes('*'));

  return (
    <Stack gap={2}>
      <Text size="sm" ff="monospace">
        {e.replyAddresses.join(', ')}
      </Text>
      {patterns.length > 0 || e.replyAddressesCapped ? (
        <Text size="xs" c={e.replyAddressesCapped ? 'orange' : resolved.length === 0 ? 'orange' : 'dimmed'}>
          {e.replyAddressesCapped
            ? `too broad — only the first ${resolved.length} addresses are traced`
            : resolved.length === 0
              ? 'no matching queue yet'
              : `${resolved.length} matching now`}
        </Text>
      ) : null}
    </Stack>
  );
}

/** Which request addresses are traced, and how (request-reply-tracing spec). */
export function ExpectationsView({ clusterId }: { clusterId: string }) {
  const expectations = useRrExpectations(clusterId);
  const create = useCreateRrExpectation(clusterId);
  const update = useUpdateRrExpectation(clusterId);
  const remove = useDeleteRrExpectation(clusterId);

  const [requestAddress, setRequestAddress] = useState('');
  const [replyAddresses, setReplyAddresses] = useState<string[]>([]);
  const [deadlineMs, setDeadlineMs] = useState<number | ''>('');
  const [samplePerMin, setSamplePerMin] = useState<number | ''>(10);
  const [capturePayload, setCapturePayload] = useState(false);

  // Every mutation reports its own failure. A silently ignored error here reads as
  // a switch that flipped back on its own, which is the least diagnosable outcome.
  const failed = (error: Error) => notifications.show({ message: error.message, color: 'red' });

  const submit = () => {
    if (!requestAddress.trim()) return;
    create.mutate(
      {
        requestAddress: requestAddress.trim(),
        replyAddresses,
        correlationProperty: undefined,
        deadlineMs: deadlineMs === '' ? undefined : deadlineMs,
        samplePerMin: samplePerMin === '' ? 10 : samplePerMin,
        capturePayload,
      },
      {
        onSuccess: () => {
          setRequestAddress('');
          setReplyAddresses([]);
          setDeadlineMs('');
          setSamplePerMin(10);
          setCapturePayload(false);
          notifications.show({ message: `Tracing ${requestAddress.trim()}`, color: 'green' });
        },
        onError: failed,
      },
    );
  };

  const toggle = (e: ExpectationView) => {
    update.mutate(
      {
        id: e.id,
        body: {
          replyAddresses: e.replyAddresses,
          correlationProperty: e.correlationProperty ?? undefined,
          deadlineMs: e.deadlineMs ?? undefined,
          samplePerMin: e.samplePerMin,
          capturePayload: e.capturePayload,
          enabled: !e.enabled,
        },
      },
      { onError: failed },
    );
  };

  return (
    <Stack gap="md">
      <Title order={4}>Traced addresses</Title>
      <Text size="sm" c="dimmed">
        Declare which request-reply addresses Studio should reconstruct flows for. Tracing is
        sampled — see the Latency tab for what that means for reported numbers.
      </Text>

      <Group align="flex-end" gap="xs">
        <AddressPicker
          clusterId={clusterId}
          label="Request address"
          placeholder="orders.request"
          value={requestAddress}
          onChange={setRequestAddress}
          unknownHint="No address on this cluster has that name yet."
          w={240}
        />
        <ReplyAddressesInput
          clusterId={clusterId}
          value={replyAddresses}
          onChange={setReplyAddresses}
          w={320}
        />
        <NumberInput
          label="Deadline (ms)"
          placeholder="from message"
          value={deadlineMs}
          onChange={(v) => setDeadlineMs(typeof v === 'number' ? v : '')}
          w={140}
        />
        <NumberInput
          label="Samples/min"
          value={samplePerMin}
          onChange={(v) => setSamplePerMin(typeof v === 'number' ? v : '')}
          min={1}
          w={110}
        />
        <Checkbox
          label="Capture payload"
          checked={capturePayload}
          onChange={(e) => setCapturePayload(e.currentTarget.checked)}
          mb={8}
        />
        <Button onClick={submit} loading={create.isPending} disabled={!requestAddress.trim()}>
          Add
        </Button>
      </Group>

      {expectations.isPending ? (
        <Text size="sm" c="dimmed">
          Loading…
        </Text>
      ) : (expectations.data ?? []).length === 0 ? (
        <Text size="sm" c="dimmed">
          No addresses declared yet — traffic on this cluster is not being traced.
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Request address</Table.Th>
              <Table.Th>Reply addresses</Table.Th>
              <Table.Th>Deadline</Table.Th>
              <Table.Th>Samples/min</Table.Th>
              <Table.Th>Payload</Table.Th>
              <Table.Th>Enabled</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(expectations.data ?? []).map((e) => (
              <Table.Tr key={e.id}>
                <Table.Td>
                  <Text size="sm" ff="monospace">
                    {e.requestAddress}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <ReplyAddressesCell expectation={e} />
                </Table.Td>
                <Table.Td>{e.deadlineMs != null ? `${e.deadlineMs}ms` : 'from message'}</Table.Td>
                <Table.Td>{e.samplePerMin}</Table.Td>
                <Table.Td>{e.capturePayload ? 'yes' : 'no'}</Table.Td>
                <Table.Td>
                  <Switch checked={e.enabled} onChange={() => toggle(e)} size="sm" />
                </Table.Td>
                <Table.Td>
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    onClick={() =>
                      remove.mutate(e.id, {
                        onError: failed,
                        onSuccess: () =>
                          notifications.show({
                            message: `Stopped tracing ${e.requestAddress}`,
                            color: 'green',
                          }),
                      })
                    }
                    aria-label={`Remove ${e.requestAddress}`}
                  >
                    ×
                  </ActionIcon>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

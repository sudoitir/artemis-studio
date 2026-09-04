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
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';

import {
  useCreateRrExpectation,
  useDeleteRrExpectation,
  useRrExpectations,
  useUpdateRrExpectation,
  type ExpectationView,
} from '../api/client.ts';

/** Which request addresses are traced, and how (request-reply-tracing spec). */
export function ExpectationsView({ clusterId }: { clusterId: string }) {
  const expectations = useRrExpectations(clusterId);
  const create = useCreateRrExpectation(clusterId);
  const update = useUpdateRrExpectation(clusterId);
  const remove = useDeleteRrExpectation(clusterId);

  const [requestAddress, setRequestAddress] = useState('');
  const [replyAddress, setReplyAddress] = useState('');
  const [deadlineMs, setDeadlineMs] = useState<number | ''>('');
  const [samplePerMin, setSamplePerMin] = useState<number | ''>(10);
  const [capturePayload, setCapturePayload] = useState(false);

  const submit = () => {
    if (!requestAddress.trim()) return;
    create.mutate(
      {
        requestAddress: requestAddress.trim(),
        replyAddress: replyAddress.trim() || undefined,
        correlationProperty: undefined,
        deadlineMs: deadlineMs === '' ? undefined : deadlineMs,
        samplePerMin: samplePerMin === '' ? 10 : samplePerMin,
        capturePayload,
      },
      {
        onSuccess: () => {
          setRequestAddress('');
          setReplyAddress('');
          setDeadlineMs('');
          setSamplePerMin(10);
          setCapturePayload(false);
          notifications.show({ message: `Tracing ${requestAddress.trim()}`, color: 'green' });
        },
        onError: (e) => notifications.show({ message: e.message, color: 'red' }),
      },
    );
  };

  const toggle = (e: ExpectationView) => {
    update.mutate({
      id: e.id,
      body: {
        replyAddress: e.replyAddress ?? undefined,
        correlationProperty: e.correlationProperty ?? undefined,
        deadlineMs: e.deadlineMs ?? undefined,
        samplePerMin: e.samplePerMin,
        capturePayload: e.capturePayload,
        enabled: !e.enabled,
      },
    });
  };

  return (
    <Stack gap="md">
      <Title order={4}>Traced addresses</Title>
      <Text size="sm" c="dimmed">
        Declare which request-reply addresses Studio should reconstruct flows for. Tracing is
        sampled — see the Latency tab for what that means for reported numbers.
      </Text>

      <Group align="flex-end" gap="xs">
        <TextInput
          label="Request address"
          placeholder="orders.request"
          value={requestAddress}
          onChange={(e) => setRequestAddress(e.currentTarget.value)}
          w={200}
        />
        <TextInput
          label="Reply address"
          description="Only for the shared-reply-queue pattern"
          placeholder="orders.reply"
          value={replyAddress}
          onChange={(e) => setReplyAddress(e.currentTarget.value)}
          w={200}
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
              <Table.Th>Reply address</Table.Th>
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
                <Table.Td>{e.replyAddress ?? '—'}</Table.Td>
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
                    onClick={() => remove.mutate(e.id)}
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

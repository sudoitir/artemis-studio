import { useState } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Group,
  PasswordInput,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { IconSend, IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';

import {
  useCreateNotificationChannel,
  useDeleteNotificationChannel,
  useNotificationChannels,
  useTestNotificationChannel,
} from '../api/client.ts';

const KIND_OPTIONS = [
  { value: 'SLACK', label: 'Slack (incoming webhook)' },
  { value: 'WEBHOOK', label: 'Generic webhook (signed)' },
];

/**
 * Global notification channels — a Slack incoming webhook or a signed generic
 * webhook (alerting spec, ADR-0036). The secret is write-only: the field is
 * always empty on this screen, since the API never returns it in plaintext.
 */
export function NotificationChannels() {
  const channels = useNotificationChannels();
  const create = useCreateNotificationChannel();
  const remove = useDeleteNotificationChannel();
  const test = useTestNotificationChannel();

  const [name, setName] = useState('');
  const [kind, setKind] = useState<string | null>('SLACK');
  const [secret, setSecret] = useState('');
  const [url, setUrl] = useState('');

  const valid = name.trim() && kind && secret.trim() && (kind !== 'WEBHOOK' || url.trim());

  const submit = () => {
    if (!valid) return;
    create.mutate(
      {
        name: name.trim(),
        kind: kind!,
        config: kind === 'WEBHOOK' ? JSON.stringify({ url: url.trim() }) : '{}',
        secret: secret.trim(),
        enabled: true,
      },
      {
        onSuccess: () => {
          setName('');
          setSecret('');
          setUrl('');
          notifications.show({ message: `Added "${name.trim()}"`, color: 'green' });
        },
        onError: (e) => notifications.show({ message: e.message, color: 'red' }),
      },
    );
  };

  return (
    <Stack gap="md">
      <Group align="flex-end" gap="xs" wrap="wrap">
        <TextInput
          label="Name"
          placeholder="ops-slack"
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
          w={180}
        />
        <Select label="Kind" data={KIND_OPTIONS} value={kind} onChange={setKind} w={220} allowDeselect={false} />
        {kind === 'WEBHOOK' ? (
          <TextInput
            label="URL"
            placeholder="https://example.com/hooks/alerts"
            value={url}
            onChange={(e) => setUrl(e.currentTarget.value)}
            w={260}
          />
        ) : null}
        <PasswordInput
          label={kind === 'SLACK' ? 'Webhook URL' : 'Signing secret'}
          placeholder={kind === 'SLACK' ? 'https://hooks.slack.com/services/…' : 'whsec_…'}
          value={secret}
          onChange={(e) => setSecret(e.currentTarget.value)}
          w={280}
        />
        <Button onClick={submit} loading={create.isPending} disabled={!valid}>
          Add channel
        </Button>
      </Group>

      {channels.isPending ? (
        <Text size="sm" c="dimmed">
          Loading…
        </Text>
      ) : (channels.data ?? []).length === 0 ? (
        <Text size="sm" c="dimmed">
          No notification channels configured — alert rules can still fire and record history,
          they just won't deliver anywhere until a channel is bound.
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name</Table.Th>
              <Table.Th>Kind</Table.Th>
              <Table.Th>Secret</Table.Th>
              <Table.Th>Enabled</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(channels.data ?? []).map((c) => (
              <Table.Tr key={c.id}>
                <Table.Td>{c.name}</Table.Td>
                <Table.Td>
                  <Badge size="xs" variant="light">
                    {c.kind}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Text size="xs" c="dimmed">
                    {c.hasSecret ? 'configured' : 'not set'}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">{c.enabled ? 'yes' : 'no'}</Text>
                </Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    <ActionIcon
                      variant="subtle"
                      onClick={() =>
                        test.mutate(c.id, {
                          onSuccess: () =>
                            notifications.show({ message: `Test sent to "${c.name}"`, color: 'green' }),
                          onError: (e) => notifications.show({ message: e.message, color: 'red' }),
                        })
                      }
                      loading={test.isPending}
                      aria-label={`Send test notification to ${c.name}`}
                    >
                      <IconSend size={16} />
                    </ActionIcon>
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      onClick={() => remove.mutate(c.id)}
                      aria-label={`Delete ${c.name}`}
                    >
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

import { useState } from 'react';
import {
  ActionIcon,
  Button,
  Group,
  Modal,
  NumberInput,
  Stack,
  Switch,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';

import { useSendMessage } from '../api/client.ts';

type Pair = { k: string; v: string };

/** Enqueue one message. Over Jolokia the body is text; binary is Phase 4 (non-negotiable #5). */
export function SendMessage({
  clusterId,
  queueName,
  node,
  opened,
  onClose,
}: {
  clusterId: string;
  queueName: string;
  node?: string;
  opened: boolean;
  onClose: () => void;
}) {
  const send = useSendMessage(clusterId, queueName);
  const [type, setType] = useState(3);
  const [durable, setDurable] = useState(true);
  const [body, setBody] = useState('');
  const [props, setProps] = useState<Pair[]>([]);

  const submit = () => {
    const properties = Object.fromEntries(props.filter((p) => p.k).map((p) => [p.k, p.v]));
    send.mutate(
      { body: { type, durable, body, headers: {}, properties }, node },
      {
        onSuccess: () => {
          notifications.show({ message: 'Message sent' });
          setBody('');
          setProps([]);
          onClose();
        },
        onError: (e) => notifications.show({ color: 'red', message: e.message }),
      },
    );
  };

  return (
    <Modal opened={opened} onClose={onClose} title={`Send to ${queueName}`} size="lg">
      <Stack gap="sm">
        <Group gap="sm" align="flex-end">
          <NumberInput label="Type" value={type} onChange={(v) => setType(Number(v) || 0)} w={100} size="xs" />
          <Switch
            label="Durable"
            checked={durable}
            onChange={(e) => setDurable(e.currentTarget.checked)}
          />
        </Group>
        <Textarea
          label="Body (text)"
          value={body}
          onChange={(e) => setBody(e.currentTarget.value)}
          autosize
          minRows={4}
        />
        <Stack gap={4}>
          <Group justify="space-between">
            <Text size="xs" fw={600} c="dimmed">
              Properties
            </Text>
            <ActionIcon
              size="sm"
              variant="subtle"
              aria-label="Add property"
              onClick={() => setProps((p) => [...p, { k: '', v: '' }])}
            >
              <IconPlus size={14} />
            </ActionIcon>
          </Group>
          {props.map((p, i) => (
            <Group key={i} gap="xs" wrap="nowrap">
              <TextInput
                placeholder="key"
                value={p.k}
                size="xs"
                onChange={(e) =>
                  setProps((arr) => arr.map((x, j) => (j === i ? { ...x, k: e.currentTarget.value } : x)))
                }
              />
              <TextInput
                placeholder="value"
                value={p.v}
                size="xs"
                onChange={(e) =>
                  setProps((arr) => arr.map((x, j) => (j === i ? { ...x, v: e.currentTarget.value } : x)))
                }
              />
              <ActionIcon
                size="sm"
                variant="subtle"
                color="red"
                aria-label="Remove property"
                onClick={() => setProps((arr) => arr.filter((_, j) => j !== i))}
              >
                <IconTrash size={14} />
              </ActionIcon>
            </Group>
          ))}
        </Stack>
        <Text size="xs" c="dimmed">
          Over Jolokia the body is a string. Faithful binary bodies need the Core client.
        </Text>
        <Group justify="flex-end">
          <Button variant="default" size="xs" onClick={onClose}>
            Cancel
          </Button>
          <Button size="xs" loading={send.isPending} onClick={submit}>
            Send
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

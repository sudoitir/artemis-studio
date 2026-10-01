import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  ActionIcon,
  Button,
  Fieldset,
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

import { notify } from '../../ui/notify.ts';
import { useSendMessage } from './api.ts';
import { announceFailure, VERBS } from './outcomes.ts';

/** A property row; the id keeps it identifiable while rows are added, edited and removed. */
type Pair = { id: number; k: string; v: string };

type Errors = { type?: string; keys: Record<number, string> };

let nextPairId = 0;

const TYPE_ERROR = 'Enter a whole number: 3 is a text message.';

/** What is wrong with the form, field by field. An empty result means it can be sent. */
function check(type: number | string, props: Pair[]): Errors {
  const keys: Record<number, string> = {};
  const seen = new Set<string>();
  for (const { id, k } of props) {
    const key = k.trim();
    if (!key) keys[id] = 'Name the property, or remove the row.';
    else if (seen.has(key)) keys[id] = `Another property is already named "${key}".`;
    seen.add(key);
  }
  const valid = type !== '' && Number.isInteger(Number(type)) && Number(type) >= 0;
  return { type: valid ? undefined : TYPE_ERROR, keys };
}

const clean = (e: Errors): boolean => e.type === undefined && Object.keys(e.keys).length === 0;

/** Enqueue one message. Over Jolokia the body is text; binary is Phase 4 (non-negotiable #5). */
export function SendMessage({
  clusterId,
  queueName,
  node,
  opened,
  onClose,
}: Readonly<{
  clusterId: string;
  queueName: string;
  node?: string;
  opened: boolean;
  onClose: () => void;
}>) {
  const send = useSendMessage(clusterId, queueName);
  const [type, setType] = useState<number | string>(3);
  const [durable, setDurable] = useState(true);
  const [body, setBody] = useState('');
  const [props, setProps] = useState<Pair[]>([]);
  const [errors, setErrors] = useState<Errors>({ keys: {} });
  // Counts the submits that were refused, so the first invalid field takes focus after each.
  const [refused, setRefused] = useState(0);
  const form = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (refused > 0) form.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
  }, [refused]);

  const edit = (id: number, patch: Partial<Pair>) =>
    setProps((all) => all.map((p) => (p.id === id ? { ...p, ...patch } : p)));
  const validate = () => setErrors(check(type, props));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = check(type, props);
    setErrors(found);
    if (!clean(found)) {
      setRefused((n) => n + 1);
      return;
    }
    const properties = Object.fromEntries(props.map((p) => [p.k.trim(), p.v]));
    send.mutate(
      { body: { type: Number(type), durable, body, headers: {}, properties }, node },
      {
        onSuccess: () => {
          notify.succeeded({ action: VERBS.send, subject: `a message to queue "${queueName}"` });
          setBody('');
          setProps([]);
          setErrors({ keys: {} });
          onClose();
        },
        onError: (e) => announceFailure('send', `a message to queue "${queueName}"`, e),
      },
    );
  };

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={`Send to ${queueName}`}
      size="lg"
      closeOnEscape={!send.isPending}
      closeOnClickOutside={!send.isPending}
    >
      <form ref={form} onSubmit={submit} noValidate>
        <Stack gap="sm">
          <Group gap="md" align="flex-start">
            <NumberInput
              label="Type"
              description="3 is a text message"
              value={type}
              error={errors.type}
              onChange={setType}
              onBlur={validate}
              allowDecimal={false}
              min={0}
              size="xs"
              data-autofocus
            />
            <Switch label="Durable" checked={durable} onChange={(e) => setDurable(e.currentTarget.checked)} />
          </Group>
          <Textarea
            label="Body (text)"
            description="Over Jolokia the body is a string. Faithful binary bodies need the Core client."
            value={body}
            onChange={(e) => setBody(e.currentTarget.value)}
            autosize
            minRows={4}
          />
          <Fieldset legend="Properties">
            <Stack gap="xs">
              {props.length === 0 ? (
                <Text size="sm" c="dimmed">
                  No properties. A property is a named value a consumer can select on.
                </Text>
              ) : null}
              {props.map((p, i) => (
                <Group key={p.id} gap="xs" align="flex-start" wrap="nowrap">
                  <TextInput
                    label="Key"
                    value={p.k}
                    error={errors.keys[p.id]}
                    size="xs"
                    onChange={(e) => edit(p.id, { k: e.currentTarget.value })}
                    onBlur={validate}
                  />
                  <TextInput
                    label="Value"
                    value={p.v}
                    size="xs"
                    onChange={(e) => edit(p.id, { v: e.currentTarget.value })}
                  />
                  <ActionIcon
                    variant="subtle"
                    aria-label={`Remove property ${i + 1}`}
                    mt="lg"
                    onClick={() => setProps((all) => all.filter((x) => x.id !== p.id))}
                  >
                    <IconTrash size="1rem" aria-hidden />
                  </ActionIcon>
                </Group>
              ))}
              <div>
                <Button
                  size="xs"
                  variant="default"
                  leftSection={<IconPlus size="1rem" aria-hidden />}
                  onClick={() => setProps((all) => [...all, { id: nextPairId++, k: '', v: '' }])}
                >
                  Add property
                </Button>
              </div>
            </Stack>
          </Fieldset>
          <Group justify="flex-end">
            <Button variant="default" size="xs" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" size="xs" loading={send.isPending}>
              Send
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

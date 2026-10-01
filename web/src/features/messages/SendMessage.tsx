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
import { useForm } from '@mantine/form';
import { randomId } from '@mantine/hooks';
import { IconPlus, IconTrash } from '@tabler/icons-react';

import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify } from '../../ui/notify.ts';
import { useSendMessage } from './api.ts';
import { announceFailure, VERBS } from './outcomes.ts';

/** A property row; the id keeps it identifiable while rows are added, edited and removed. */
type Pair = { id: string; k: string; v: string };

interface FormState {
  type: number | string;
  durable: boolean;
  body: string;
  props: Pair[];
}

const TYPE_ERROR = 'Enter a whole number: 3 is a text message.';

/** What is wrong with the form, field by field, keyed by the field's path. An empty result means it can be sent. */
function check({ type, props }: FormState): Record<string, string> {
  const errors: Record<string, string> = {};
  const seen = new Set<string>();
  props.forEach(({ k }, i) => {
    const key = k.trim();
    if (!key) errors[`props.${i}.k`] = 'Name the property, or remove the row.';
    else if (seen.has(key)) errors[`props.${i}.k`] = `Another property is already named "${key}".`;
    seen.add(key);
  });
  const valid = type !== '' && Number.isInteger(Number(type)) && Number(type) >= 0;
  if (!valid) errors.type = TYPE_ERROR;
  return errors;
}

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
  const form = useForm<FormState>({
    initialValues: { type: 3, durable: true, body: '', props: [] },
    validateInputOnBlur: true,
    validate: check,
  });

  const submit = form.onSubmit(({ type, durable, body, props }) => {
    const properties = Object.fromEntries(props.map((p) => [p.k.trim(), p.v]));
    send.mutate(
      { body: { type: Number(type), durable, body, headers: {}, properties }, node },
      {
        onSuccess: () => {
          notify.succeeded({ action: VERBS.send, subject: `a message to queue "${queueName}"` });
          form.setFieldValue('body', '');
          form.setFieldValue('props', []);
          form.clearErrors();
          onClose();
        },
        onError: (e) => announceFailure('send', `a message to queue "${queueName}"`, e),
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={`Send to ${queueName}`}
      size="lg"
      closeOnEscape={!send.isPending}
      closeOnClickOutside={!send.isPending}
    >
      <form onSubmit={submit} noValidate>
        <Stack gap="sm">
          <FieldRow>
            <NumberInput
              label="Type"
              description="3 is a text message"
              {...form.getInputProps('type')}
              allowDecimal={false}
              min={0}
              size="xs"
              data-autofocus
            />
            <Switch label="Durable" {...form.getInputProps('durable', { type: 'checkbox' })} />
          </FieldRow>
          <Textarea
            label="Body (text)"
            description="Over Jolokia the body is a string. Faithful binary bodies need the Core client."
            {...form.getInputProps('body')}
            autosize
            minRows={4}
          />
          <Fieldset legend="Properties">
            <Stack gap="xs">
              {form.values.props.length === 0 ? (
                <Text size="sm" c="dimmed">
                  No properties. A property is a named value a consumer can select on.
                </Text>
              ) : null}
              {form.values.props.map((p, i) => (
                <FieldRow key={p.id}>
                  <TextInput label="Key" size="xs" {...form.getInputProps(`props.${i}.k`)} />
                  <TextInput label="Value" size="xs" {...form.getInputProps(`props.${i}.v`)} />
                  <ActionIcon
                    variant="subtle"
                    aria-label={`Remove property ${i + 1}`}
                    onClick={() => form.removeListItem('props', i)}
                  >
                    <IconTrash size="1rem" aria-hidden />
                  </ActionIcon>
                </FieldRow>
              ))}
              <div>
                <Button
                  size="xs"
                  variant="default"
                  leftSection={<IconPlus size="1rem" aria-hidden />}
                  onClick={() => form.insertListItem('props', { id: randomId(), k: '', v: '' })}
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

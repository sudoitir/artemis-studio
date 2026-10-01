import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Button, Combobox, Group, Radio, Stack, Switch, Text, TextInput, useCombobox } from '@mantine/core';
import { useForm } from '@mantine/form';

import type { ConfigDeclarationView } from './api.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { upsertAddress } from './document.ts';
import { useSaveDocument } from './useSaveDocument.ts';
import classes from './Configuration.module.css';

const CREATE = '\u0000create';

type RoutingType = 'ANYCAST' | 'MULTICAST';

/** Why `name` cannot be a new queue, or null when it can. */
function queueNameProblem(declaration: ConfigDeclarationView, name: string): string | null {
  const n = name.trim();
  if (!n) return 'A queue name is required.';
  if (/\s/.test(n)) return 'A queue name cannot contain spaces.';
  if (/[*#]/.test(n)) return '"*" and "#" are Artemis wildcards; a queue name cannot contain them.';
  const owner = declaration.document.addresses.find((a) => a.queues.some((q) => q.name === n));
  if (owner) return `Queue "${n}" is already declared, on address ${owner.name}.`;
  return null;
}

/**
 * A divert's "To address": the declared addresses to choose from, and — when what is typed is
 * not one of them — a last option that creates a queue for it in the declaration, inline, without
 * leaving the divert (ADR-0094).
 *
 * <p>The queue is declared, not created on a broker: it saves as its own revision ("Added queue
 * X"), shows on the canvas at once as declared and not yet applied, and reaches the brokers in the
 * same apply as the divert that targets it — so the divert never goes live ahead of its queue.
 *
 * <p>Keyboard: the arrow keys and Enter pick the create option; Enter in the inline section adds
 * the queue; Escape collapses it and puts focus back on this field without closing the drawer.
 * On success focus returns here too, and a live region says what happened.
 */
export function ForwardingAddressField({
  declaration,
  value,
  onChange,
  onBlur,
  error,
  'data-path': dataPath,
  onCreatingChange,
}: Readonly<{
  declaration: ConfigDeclarationView;
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  error?: ReactNode;
  /** Set by `form.getInputProps`, so `form.getInputNode` finds the input. */
  'data-path'?: string;
  /** Whether the inline section is open, so the drawer can leave Escape to it. */
  onCreatingChange: (creating: boolean) => void;
}>) {
  const combobox = useCombobox({ onDropdownClose: () => combobox.resetSelectedOption() });
  const field = useRef<HTMLInputElement>(null);

  // The inline section: which address it creates, and the queue it declares on it.
  const [creating, setCreating] = useState<string | null>(null);
  const queue = useForm<{ queueName: string; routingType: RoutingType; durable: boolean }>({
    initialValues: { queueName: '', routingType: 'ANYCAST', durable: true },
    validateInputOnBlur: true,
    validate: { queueName: (v) => queueNameProblem(declaration, v) },
  });
  const [announcement, setAnnouncement] = useState('');

  const declared = declaration.document.addresses.map((a) => a.name);
  const typed = value.trim();
  const exact = declared.includes(typed);
  const matching = exact ? declared : declared.filter((n) => n.toLowerCase().includes(typed.toLowerCase()));
  const addressProblem = typed && !exact ? queueNameProblem(declaration, typed) : null;

  const setOpen = (address: string | null) => {
    setCreating(address);
    onCreatingChange(address !== null);
  };

  const collapse = () => {
    setOpen(null);
    field.current?.focus();
  };

  const added = (address: string, queueName: string) => {
    onChange(address);
    setOpen(null);
    setAnnouncement(`Queue ${queueName} added to the declaration — apply to create it on the brokers.`);
    field.current?.focus();
  };
  const {
    save,
    isPending,
    error: saveError,
    reset,
  } = useSaveDocument(declaration, () => added(creating!, queue.values.queueName.trim()));

  const openCreate = () => {
    queue.setValues({ queueName: typed, routingType: 'ANYCAST', durable: true });
    queue.clearErrors();
    setAnnouncement('');
    reset();
    setOpen(typed);
    requestAnimationFrame(() => queue.getInputNode('queueName')?.focus());
  };

  const submit = queue.onSubmit(({ queueName, routingType, durable }) => {
    if (creating === null || isPending) return;
    save(
      upsertAddress(declaration.document, {
        name: creating,
        routingTypes: [routingType],
        queues: [{ name: queueName.trim(), routingType, durable }],
      }),
      `Added queue ${queueName.trim()}`,
    );
  }, focusFirstInvalid(queue.getInputNode));

  const onSectionKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      collapse();
    } else if (event.key === 'Enter' && (event.target as HTMLElement).tagName === 'INPUT') {
      event.preventDefault();
      submit();
    }
  };

  return (
    <Stack gap="xs">
      <Combobox
        store={combobox}
        onOptionSubmit={(v) => {
          combobox.closeDropdown();
          if (v === CREATE) openCreate();
          else onChange(v);
        }}
      >
        <Combobox.Target withExpandedAttribute>
          <TextInput
            ref={field}
            data-path={dataPath}
            label="To address"
            description="Choose a declared address, or type a new one and create its queue. An address with no queue drops what is diverted to it."
            value={value}
            onChange={(e) => {
              onChange(e.currentTarget.value);
              setAnnouncement('');
              combobox.openDropdown();
              combobox.updateSelectedOptionIndex();
            }}
            onClick={() => combobox.openDropdown()}
            onBlur={() => {
              combobox.closeDropdown();
              onBlur?.();
            }}
            error={error}
            rightSection={<Combobox.Chevron />}
            rightSectionPointerEvents="none"
            required
          />
        </Combobox.Target>
        <Combobox.Dropdown>
          <Combobox.Options>
            {matching.map((name) => (
              <Combobox.Option value={name} key={name}>
                {name}
              </Combobox.Option>
            ))}
            {typed && !exact ? (
              <Combobox.Option value={CREATE} disabled={addressProblem !== null}>
                <Text size="sm" fw={500}>
                  Create queue “{typed}”…
                </Text>
                <Text size="xs" c="dimmed">
                  {addressProblem ?? 'Declares the address and its queue; applied with this divert.'}
                </Text>
              </Combobox.Option>
            ) : null}
            {matching.length === 0 && !typed ? (
              <Combobox.Empty>No address is declared yet. Type one.</Combobox.Empty>
            ) : null}
          </Combobox.Options>
        </Combobox.Dropdown>
      </Combobox>

      {creating !== null ? (
        <fieldset
          className={classes.inlineSection}
          aria-label={`Create queue on address ${creating}`}
          onKeyDown={onSectionKeyDown}
        >
          <Stack gap="sm">
            <Text size="sm" fw={600}>
              New queue on address {creating}
            </Text>
            <TextInput label="Queue name" {...queue.getInputProps('queueName')} required />
            <Radio.Group label="Routing type" {...queue.getInputProps('routingType')}>
              <Group gap="lg" mt="xs">
                <Radio value="ANYCAST" label="Anycast — each message to one consumer" />
                <Radio value="MULTICAST" label="Multicast — to every subscriber" />
              </Group>
            </Radio.Group>
            <Switch
              label="Durable"
              description="Messages survive a broker restart."
              {...queue.getInputProps('durable', { type: 'checkbox' })}
            />
            {saveError ? (
              <Stack gap="sm">
                <ErrorState error={saveError} />
                {saveError.type.endsWith('stale-revision') ? (
                  <Text size="sm">
                    Someone saved the declaration while this was open; close the editor and add the queue again on top
                    of their revision.
                  </Text>
                ) : null}
              </Stack>
            ) : null}
            <Group gap="xs" justify="flex-end">
              <Button variant="default" size="xs" onClick={collapse}>
                Cancel
              </Button>
              <Button size="xs" onClick={() => submit()} loading={isPending}>
                Add queue to the declaration
              </Button>
            </Group>
            <Text size="xs" c="dimmed">
              Saved as revision {declaration.revision + 1} on its own. Nothing reaches a broker until the declaration is
              applied.
            </Text>
          </Stack>
        </fieldset>
      ) : null}

      <Text size="xs" role="status" aria-live="polite">
        {announcement}
      </Text>
    </Stack>
  );
}

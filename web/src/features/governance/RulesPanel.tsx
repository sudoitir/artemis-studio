import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Group,
  Modal,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  VisuallyHidden,
} from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import {
  useCreateRule,
  useDeleteRule,
  useRemaskProgress,
  useRules,
  useUpdateRule,
  withEnabled,
  type RuleRequest,
  type RuleView,
} from './api.ts';

/** Whether stored messages have caught up with the policy. Reads always apply the current policy either way. */
function RemaskStatus() {
  const progress = useRemaskProgress();
  if (progress.isPending) {
    return <Text size="sm">Checking whether stored messages are masked under the current policy…</Text>;
  }
  if (progress.isError) {
    return (
      <Text size="sm">
        Could not check whether stored messages are masked under the current policy: {progress.error.message}
      </Text>
    );
  }
  const { rowsUnderEarlierVersion: rows, capped, version } = progress.data;
  if (rows === 0) {
    return <Text size="sm">Every stored message is masked under the current policy (version {version}).</Text>;
  }
  return (
    <Text size="sm" style={{ fontVariantNumeric: 'tabular-nums' }}>
      {capped ? 'More than ' : ''}
      {rows.toLocaleString()} stored message{rows === 1 ? ' is' : 's are'} still masked under an earlier policy.
      Re-masking runs in the background; reads already apply version {version}.
    </Text>
  );
}

const TARGETS = [
  { value: 'PROPERTY', label: 'Property' },
  { value: 'HEADER', label: 'Header' },
  { value: 'BODY_PATH', label: 'JSON body path' },
];

const CLASSES = [
  { value: 'CREDENTIAL', label: 'Credential' },
  { value: 'PAN', label: 'Payment card number' },
  { value: 'IBAN', label: 'IBAN' },
  { value: 'EMAIL', label: 'Email' },
  { value: 'PHONE', label: 'Phone number' },
  { value: 'NATIONAL_ID', label: 'National identifier' },
  { value: 'PERSONAL', label: 'Personal data' },
];

const ACTIONS = [
  { value: 'DEFAULT', label: 'The class default' },
  { value: 'DROP', label: 'Drop — never shown' },
  { value: 'PARTIAL', label: 'Partial — last four kept' },
  { value: 'REDACT', label: 'Redact — whole value' },
];

const ACTION_WORDS: Record<string, string> = { DROP: 'Drop', PARTIAL: 'Partial', REDACT: 'Redact', CLEAR: 'Leave clear' };

const WRITE_REASON = 'Changing masking rules needs the governance:write permission.';

type Errors = { selector?: string; addressPattern?: string };

const EMPTY: RuleRequest = {
  addressPattern: null,
  target: 'PROPERTY',
  selector: '',
  dataClass: 'PERSONAL',
  action: null,
  enabled: true,
};

function validate(form: RuleRequest): Errors {
  const errors: Errors = {};
  if (form.selector.trim() === '') {
    errors.selector =
      form.target === 'BODY_PATH'
        ? 'Enter the JSON path the rule matches, such as payment.card.'
        : 'Enter the name the rule matches. Use * for any run of characters.';
  }
  if (form.addressPattern && /\s/.test(form.addressPattern)) {
    errors.addressPattern = 'An address pattern has no spaces. Use a pattern such as orders.# or orders.*.';
  }
  return errors;
}

/** The content policy's masking rules (data-governance spec). Built-in credential rules can be disabled, never deleted. */
export function RulesPanel() {
  const rules = useRules();
  const create = useCreateRule();
  const update = useUpdateRule();
  const remove = useDeleteRule();
  const { can, loading } = useCan();
  // While grants load, offer the control: the server decides (operator-ui spec).
  const canWrite = loading || can('governance:write');

  const [editing, setEditing] = useState<RuleView | 'new' | null>(null);
  const [form, setForm] = useState<RuleRequest>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [saveError, setSaveError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<RuleView | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [failure, setFailure] = useState<string | null>(null);

  function openNew() {
    setEditing('new');
    setForm(EMPTY);
    setErrors({});
    setSaveError(null);
  }

  function openEdit(rule: RuleView) {
    setEditing(rule);
    setForm(withEnabled(rule, rule.enabled));
    setErrors({});
    setSaveError(null);
  }

  function blur(field: keyof Errors) {
    const next = validate(form);
    setErrors((prev) => ({ ...prev, [field]: next[field] }));
  }

  function save() {
    const next = validate(form);
    setErrors(next);
    const firstInvalid = next.selector ? 'rule-selector' : next.addressPattern ? 'rule-address' : null;
    if (firstInvalid) {
      document.getElementById(firstInvalid)?.focus();
      return;
    }
    const body: RuleRequest = {
      ...form,
      selector: form.selector.trim(),
      addressPattern: form.addressPattern?.trim() ? form.addressPattern.trim() : null,
    };
    const handlers = {
      onSuccess: (rule: RuleView) => {
        setEditing(null);
        setAnnouncement(`Saved the rule for ${rule.selector}.`);
      },
      onError: (e: Error) => setSaveError(`${e.message} Check the fields and save again.`),
    };
    if (editing === 'new') create.mutate(body, handlers);
    else if (editing) update.mutate({ ruleId: editing.id, body }, handlers);
  }

  function toggle(rule: RuleView, enabled: boolean) {
    setFailure(null);
    update.mutate(
      { ruleId: rule.id, body: withEnabled(rule, enabled) },
      {
        onSuccess: (saved) =>
          setAnnouncement(`${saved.enabled ? 'Enabled' : 'Disabled'} the rule for ${saved.selector}.`),
        onError: (e) => {
          setFailure(`The rule for ${rule.selector} was not changed: ${e.message}`);
          setAnnouncement(`The rule for ${rule.selector} was not changed.`);
        },
      },
    );
  }

  function confirmDelete(rule: RuleView) {
    remove.mutate(rule.id, {
      onSuccess: () => {
        setDeleting(null);
        setAnnouncement(`Deleted the rule for ${rule.selector}.`);
      },
      onError: (e) => {
        setDeleting(null);
        setFailure(`The rule for ${rule.selector} was not deleted: ${e.message}`);
        setAnnouncement(`The rule for ${rule.selector} was not deleted.`);
      },
    });
  }

  if (rules.isPending) {
    return <Text size="sm">Loading masking rules…</Text>;
  }
  if (rules.isError) {
    return (
      <Alert variant="light" color="red" title="Masking rules could not be loaded">
        <Stack gap="xs">
          <Text size="sm">{rules.error.message}</Text>
          <Group>
            <Button size="xs" variant="default" onClick={() => rules.refetch()}>
              Try again
            </Button>
          </Group>
        </Stack>
      </Alert>
    );
  }

  return (
    <Stack gap="md">
      <VisuallyHidden>
        <div role="status" aria-live="polite">
          {announcement}
        </div>
      </VisuallyHidden>

      <Stack gap={4}>
        <Text size="sm">
          A rule masks a header, a property or a JSON body value wherever message content leaves Studio or is
          stored. Values the detectors recognise are masked even without a rule. Credentials are never shown, and
          users with <code>message:clear</code> see every other value in clear.
        </Text>
        {canWrite ? null : (
          <Text size="sm" c="dimmed">
            {WRITE_REASON}
          </Text>
        )}
        <RemaskStatus />
      </Stack>

      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {rules.data.length} rule{rules.data.length === 1 ? '' : 's'}
        </Text>
        <Button size="xs" onClick={openNew} disabled={!canWrite}>
          New rule
        </Button>
      </Group>

      {failure ? (
        <Alert variant="light" color="red" title="The change did not apply" withCloseButton onClose={() => setFailure(null)}>
          {failure}
        </Alert>
      ) : null}

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Matches</Table.Th>
            <Table.Th>Addresses</Table.Th>
            <Table.Th>Class</Table.Th>
            <Table.Th>Action</Table.Th>
            <Table.Th>Enabled</Table.Th>
            <Table.Th>
              <VisuallyHidden>Changes</VisuallyHidden>
            </Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rules.data.map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>
                <Stack gap={2}>
                  <Group gap={6}>
                    <Text size="sm" ff="monospace">
                      {r.selector}
                    </Text>
                    {r.builtin ? (
                      <Badge size="xs" variant="outline" color="gray" tt="none">
                        built-in
                      </Badge>
                    ) : null}
                    {r.exception ? (
                      <Badge size="xs" variant="outline" color="gray" tt="none">
                        dismissed finding
                      </Badge>
                    ) : null}
                  </Group>
                  <Text size="xs" c="dimmed">
                    {TARGETS.find((t) => t.value === r.target)?.label ?? r.target}
                  </Text>
                </Stack>
              </Table.Td>
              <Table.Td>
                <Text size="sm" ff={r.addressPattern ? 'monospace' : undefined}>
                  {r.addressPattern ?? 'All addresses'}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm">{r.dataClassLabel}</Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm">
                  {r.action ? ACTION_WORDS[r.action] : `${ACTION_WORDS[r.defaultAction] ?? r.defaultAction} (default)`}
                </Text>
              </Table.Td>
              <Table.Td>
                <Switch
                  checked={r.enabled}
                  disabled={!canWrite}
                  aria-label={`Enabled: ${r.selector}`}
                  onChange={(e) => toggle(r, e.currentTarget.checked)}
                />
              </Table.Td>
              <Table.Td>
                {r.builtin ? (
                  <Text size="xs" c="dimmed">
                    Can be disabled, not deleted.
                  </Text>
                ) : (
                  <Group gap={4} wrap="nowrap">
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      disabled={!canWrite}
                      onClick={() => openEdit(r)}
                      aria-label={`Edit the rule for ${r.selector}`}
                    >
                      <IconPencil size={16} aria-hidden />
                    </ActionIcon>
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      disabled={!canWrite}
                      onClick={() => setDeleting(r)}
                      aria-label={`Delete the rule for ${r.selector}`}
                    >
                      <IconTrash size={16} aria-hidden />
                    </ActionIcon>
                  </Group>
                )}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal
        opened={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? 'New masking rule' : `Edit the rule for ${form.selector}`}
        size="md"
      >
        <Stack gap="sm">
          <Select
            label="Matches a"
            data={TARGETS}
            value={form.target}
            allowDeselect={false}
            onChange={(v) => setForm({ ...form, target: v ?? 'PROPERTY' })}
          />
          <TextInput
            id="rule-selector"
            label={form.target === 'BODY_PATH' ? 'JSON path' : 'Name'}
            description={
              form.target === 'BODY_PATH'
                ? 'Dotted, with [*] for array elements: items[*].email'
                : 'Case-insensitive. * matches any run of characters: *token*'
            }
            value={form.selector}
            onChange={(e) => setForm({ ...form, selector: e.currentTarget.value })}
            onBlur={() => blur('selector')}
            error={errors.selector}
            required
          />
          <TextInput
            id="rule-address"
            label="Addresses"
            description="Optional. An address pattern such as orders.#; empty means every address."
            value={form.addressPattern ?? ''}
            onChange={(e) => setForm({ ...form, addressPattern: e.currentTarget.value })}
            onBlur={() => blur('addressPattern')}
            error={errors.addressPattern}
          />
          <Select
            label="Data class"
            data={CLASSES}
            value={form.dataClass}
            allowDeselect={false}
            onChange={(v) => setForm({ ...form, dataClass: v ?? 'PERSONAL' })}
          />
          <Select
            label="Action"
            data={ACTIONS}
            value={form.action ?? 'DEFAULT'}
            allowDeselect={false}
            onChange={(v) => setForm({ ...form, action: !v || v === 'DEFAULT' ? null : v })}
          />
          <Switch
            label="Enabled"
            checked={form.enabled}
            onChange={(e) => setForm({ ...form, enabled: e.currentTarget.checked })}
          />
          {saveError ? (
            <Alert variant="light" color="red" title="The rule was not saved">
              {saveError}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setEditing(null)}>
              Cancel
            </Button>
            <Button loading={create.isPending || update.isPending} onClick={save}>
              Save rule
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={deleting !== null}
        onClose={() => setDeleting(null)}
        title={deleting ? `Delete the rule for ${deleting.selector}` : ''}
        size="md"
      >
        {deleting ? (
          <Stack gap="sm">
            <Text size="sm">
              Values this rule masks become visible to every user who can read messages on{' '}
              {deleting.addressPattern ? <code>{deleting.addressPattern}</code> : 'every address'}, unless a detector
              still recognises them. Stored messages are re-masked under the new policy.
            </Text>
            <ConfirmByTyping
              token={deleting.selector}
              confirmLabel="Delete rule"
              loading={remove.isPending}
              onConfirm={() => confirmDelete(deleting)}
            />
          </Stack>
        ) : null}
      </Modal>
    </Stack>
  );
}

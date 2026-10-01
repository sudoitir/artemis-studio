import { useRef, useState } from 'react';
import { ActionIcon, Button, Group, Modal, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';

import { useCan } from '../../kernel/auth/useCan.ts';
import type { ApiError } from '../../kernel/api/request.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
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
import { ruleColumns } from './columns.ts';
import classes from './Governance.module.css';
import { TARGETS } from './words.ts';

const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };
const ENABLE: ActionVerb = { verb: 'Enable', past: 'Enabled', progressive: 'Enabling' };
const DISABLE: ActionVerb = { verb: 'Disable', past: 'Disabled', progressive: 'Disabling' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

const rowKey = (r: RuleView) => r.id;

/** Whether stored messages have caught up with the policy. Reads always apply the current policy either way. */
function RemaskStatus() {
  const progress = useRemaskProgress();
  let status;
  if (progress.isPending) {
    status = <Text size="sm">Checking whether stored messages are masked under the current policy…</Text>;
  } else if (progress.isError) {
    status = <ErrorState variant="inline" error={progress.error} onRetry={() => void progress.refetch()} />;
  } else {
    const { rowsUnderEarlierVersion: rows, capped, version } = progress.data;
    status =
      rows === 0 ? (
        <Text size="sm">Every stored message is masked under the current policy (version {version}).</Text>
      ) : (
        <Text size="sm" className={classes.figures}>
          {capped ? 'More than ' : ''}
          {rows.toLocaleString()} stored message{rows === 1 ? ' is' : 's are'} still masked under an earlier policy.
          Re-masking runs in the background; reads already apply version {version}.
        </Text>
      );
  }
  return <div className={classes.remask}>{status}</div>;
}

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
  const [saveError, setSaveError] = useState<ApiError | null>(null);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [deleting, setDeleting] = useState<RuleView | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const selectorInput = useRef<HTMLInputElement>(null);
  const addressInput = useRef<HTMLInputElement>(null);

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
    if (next.selector || next.addressPattern) {
      (next.selector ? selectorInput : addressInput).current?.focus();
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
        notify.succeeded({ action: SAVE, subject: `the rule for ${rule.selector}` });
      },
      onError: (e: ApiError) => setSaveError(e),
    };
    setSaveError(null);
    if (editing === 'new') create.mutate(body, handlers);
    else if (editing) update.mutate({ ruleId: editing.id, body }, handlers);
  }

  function toggle(rule: RuleView, enabled: boolean) {
    const action = enabled ? ENABLE : DISABLE;
    const subject = `the rule for ${rule.selector}`;
    update.mutate(
      { ruleId: rule.id, body: withEnabled(rule, enabled) },
      {
        onSuccess: () => notify.succeeded({ action, subject }),
        onError: (e) =>
          notify.failed({
            action,
            subject,
            cause: e.message,
            next: 'The switch shows what is stored; try again.',
          }),
      },
    );
  }

  function confirmDelete(rule: RuleView) {
    const subject = `the rule for ${rule.selector}`;
    remove.mutate(rule.id, {
      onSuccess: () => {
        setDeleteOpen(false);
        notify.succeeded({ action: DELETE, subject });
      },
      onError: (e) =>
        notify.failed({
          action: DELETE,
          subject,
          cause: e.message,
          next: 'It is still listed; try again.',
        }),
    });
  }

  const savingId = update.isPending ? update.variables.ruleId : undefined;
  // Built each render: the cells carry what is gated and busy right now.
  const columns = ruleColumns({
    enabledControl: (r) => (
      <Switch
        size="sm"
        checked={r.enabled}
        disabled={!canWrite || savingId === r.id}
        aria-label={`Enabled: ${r.selector}`}
        onChange={(e) => toggle(r, e.currentTarget.checked)}
      />
    ),
    changesControl: (r) =>
      r.builtin ? (
        <span className={classes.note}>Can be disabled, not deleted.</span>
      ) : (
        <span className={classes.controls}>
          <ActionIcon
            variant="subtle"
            disabled={!canWrite}
            onClick={() => openEdit(r)}
            aria-label={`Edit the rule for ${r.selector}`}
          >
            <IconPencil size="1rem" aria-hidden />
          </ActionIcon>
          <ActionIcon
            variant="subtle"
            disabled={!canWrite}
            onClick={() => {
              setDeleting(r);
              setDeleteOpen(true);
            }}
            aria-label={`Delete the rule for ${r.selector}`}
          >
            <IconTrash size="1rem" aria-hidden />
          </ActionIcon>
        </span>
      ),
  });

  return (
    <Section
      title="Masking rules"
      description={
        <>
          A rule masks a header, a property or a JSON body value wherever message content leaves Studio or is stored.
          Values the detectors recognise are masked even without a rule. Credentials are never shown, and users with{' '}
          <code>message:clear</code> see every other value in clear.
        </>
      }
    >
      {canWrite ? null : <Text size="sm">{WRITE_REASON}</Text>}
      <RemaskStatus />

      <DataTable
        variant="static"
        label="Masking rules"
        storageKey="governance.rules"
        columns={columns}
        data={rules.data ?? []}
        rowKey={rowKey}
        loading={rules.isPending}
        error={rules.isError ? <ErrorState error={rules.error} onRetry={() => void rules.refetch()} /> : undefined}
        toolbar={{
          start: (
            <Button size="xs" onClick={openNew} disabled={!canWrite}>
              New rule
            </Button>
          ),
          end: rules.data ? (
            <Text size="sm" c="dimmed">
              {rules.data.length} rule{rules.data.length === 1 ? '' : 's'}
            </Text>
          ) : undefined,
        }}
        empty={
          <EmptyState
            kind="empty"
            title="No masking rules"
            description="A rule names a header, property or JSON body path whose value is masked wherever message content leaves Studio. Add one with New rule."
          />
        }
      />

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
            ref={selectorInput}
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
            ref={addressInput}
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
          {saveError ? <ErrorState variant="inline" error={saveError} /> : null}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setEditing(null)} disabled={create.isPending || update.isPending}>
              Cancel
            </Button>
            <Button loading={create.isPending || update.isPending} onClick={save}>
              Save rule
            </Button>
          </Group>
        </Stack>
      </Modal>

      <ConfirmDialog
        opened={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        title={deleting ? `Delete the rule for ${deleting.selector}` : 'Delete the rule'}
        tone="danger"
        typedName={deleting?.selector}
        pending={remove.isPending}
        confirmLabel="Delete rule"
        consequence={
          deleting ? (
            <>
              Values this rule masks become visible to every user who can read messages on{' '}
              {deleting.addressPattern ? <code>{deleting.addressPattern}</code> : 'every address'}, unless a detector
              still recognises them. Stored messages are re-masked under the new policy.
            </>
          ) : null
        }
        onConfirm={() => deleting && confirmDelete(deleting)}
      />
    </Section>
  );
}

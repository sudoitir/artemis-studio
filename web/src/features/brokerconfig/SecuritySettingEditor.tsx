import { ActionIcon, Button, Checkbox, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconX } from '@tabler/icons-react';

import type { ConfigCatalogueView, ConfigDeclarationView, ConfigSecuritySettingView } from './api.ts';
import { keyTaken, removeItem, upsertSecuritySetting } from './document.ts';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Section } from '../../ui/Section.tsx';
import { EditorDrawer, MATCH_HINT } from './EditorDrawer.tsx';
import classes from './Configuration.module.css';
import { useReseedOnOpen } from './useReseedOnOpen.ts';
import { useSaveDocument } from './useSaveDocument.ts';

/** What each permission lets a role do, in the words the grid's row states. */
const PERMISSION_WORDS: Record<string, string> = {
  send: 'send messages',
  consume: 'consume messages',
  createDurableQueue: 'create durable queues',
  deleteDurableQueue: 'delete durable queues',
  createNonDurableQueue: 'create non-durable queues',
  deleteNonDurableQueue: 'delete non-durable queues',
  manage: 'send management messages',
  browse: 'browse messages',
  createAddress: 'create addresses',
  deleteAddress: 'delete addresses',
  view: 'view management attributes',
  edit: 'edit management attributes',
};

/** A role and the permissions it holds, as the form edits them. */
interface RoleRow {
  name: string;
  held: string[];
}

interface FormState {
  match: string;
  /** The role being typed into "Add a role"; not part of what is saved. */
  newRole: string;
  roles: RoleRow[];
}

function seedOf(item: ConfigSecuritySettingView | null): FormState {
  const roles: RoleRow[] = [];
  for (const [permission, holders] of Object.entries(item?.permissions ?? {})) {
    for (const name of holders) {
      const row = roles.find((r) => r.name === name);
      if (row) row.held.push(permission);
      else roles.push({ name, held: [permission] });
    }
  }
  return { match: item?.match ?? '', newRole: '', roles };
}

function toWire(roles: RoleRow[], types: string[]): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const type of types) {
    const holders = roles.filter((r) => r.held.includes(type)).map((r) => r.name);
    if (holders.length > 0) out[type] = holders;
  }
  return out;
}

/**
 * Edit one security-setting match as the permissions each role holds. Each role is a group of
 * checkboxes named in words for what they let it do, so the grid is the editing, not the meaning, and
 * a match with many roles is read down the page instead of scrolling sideways across twelve columns.
 */
export function SecuritySettingEditor({
  declaration,
  catalogue,
  item,
  opened,
  onClose,
}: Readonly<{
  declaration: ConfigDeclarationView;
  catalogue: ConfigCatalogueView;
  item: ConfigSecuritySettingView | null;
  opened: boolean;
  onClose: () => void;
}>) {
  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);
  const types = catalogue.permissionTypes;

  const form = useForm<FormState>({
    initialValues: seedOf(item),
    validateInputOnBlur: true,
    validate: {
      match: (v) => {
        const m = v.trim();
        if (!m) return 'A match pattern is required — it names the addresses these roles apply to.';
        return keyTaken(declaration.document.securitySettings, (i) => i.match, m, item?.match)
          ? `"${m}" is already declared. Edit that entry instead.`
          : null;
      },
      // The roles have no field of their own: the message sits beside the one that adds them.
      newRole: (_, values) =>
        values.roles.length === 0 ? 'Add at least one role. A match with no roles grants nothing to anyone.' : null,
    },
  });
  useReseedOnOpen(form, opened, item, seedOf);
  const { roles } = form.values;

  const addRole = () => {
    const name = form.values.newRole.trim();
    if (!name || roles.some((r) => r.name === name)) return;
    form.insertListItem('roles', { name, held: [] });
    form.setFieldValue('newRole', '');
    form.clearFieldError('newRole');
  };

  const submit = form.onSubmit(({ match, roles: rows }) => {
    const next: ConfigSecuritySettingView = { match: match.trim(), permissions: toWire(rows, types) };
    save(
      upsertSecuritySetting(declaration.document, next, item?.match),
      `${item ? 'Edited' : 'Added'} security setting ${next.match}`,
    );
  }, focusFirstInvalid(form.getInputNode));

  const remove = () =>
    save(removeItem(declaration.document, 'securitySettings', item!.match), `Removed security setting ${item!.match}`);

  const match = form.values.match.trim();
  const broad = match === '#' || match === '*';

  return (
    <EditorDrawer
      opened={opened}
      onClose={() => {
        reset();
        onClose();
      }}
      title={item ? `Security setting ${item.match}` : 'New security setting'}
      error={error}
      submitting={isPending}
      submitLabel={`Save as revision ${declaration.revision + 1}`}
      onSubmit={submit}
      hint={Object.keys(form.errors).length > 0 ? 'Fix the fields above to continue.' : undefined}
      secondary={
        item ? (
          <Button variant="subtle" size="xs" onClick={remove} loading={isPending}>
            Remove from declaration
          </Button>
        ) : null
      }
    >
      <TextInput
        label="Match pattern"
        description={
          broad
            ? `${MATCH_HINT} This match covers every address, including the management address — applying it is a High hazard because it can revoke Studio's own access.`
            : MATCH_HINT
        }
        {...form.getInputProps('match')}
        required
      />

      <FieldRow>
        <TextInput
          label="Add a role"
          description="The role name as the broker's login module reports it."
          {...form.getInputProps('newRole')}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              addRole();
            }
          }}
        />
        <Button variant="default" size="xs" onClick={addRole}>
          Add role
        </Button>
      </FieldRow>

      <Text size="sm" c="dimmed">
        <b>view</b> and <b>edit</b> are sent to the broker but it does not report them back over management (measured on
        2.44), so the plan, the verification and the drift check cannot see them. Confirm those two in the broker's own
        configuration.
      </Text>

      {roles.map(({ name, held }, i) => (
        <Section
          key={name}
          variant="card"
          headingLevel={3}
          title={name}
          description={
            held.length === 0
              ? 'may do nothing here'
              : `may ${types
                  .filter((t) => held.includes(t))
                  .map((t) => PERMISSION_WORDS[t] ?? t)
                  .join(', ')}`
          }
          actions={
            <ActionIcon
              variant="subtle"
              size="sm"
              aria-label={`Remove role ${name}`}
              onClick={() => form.removeListItem('roles', i)}
            >
              <IconX size="0.875rem" />
            </ActionIcon>
          }
        >
          <Checkbox.Group label={`What ${name} may do`} {...form.getInputProps(`roles.${i}.held`)}>
            <div className={classes.permissions}>
              {types.map((t) => (
                <Checkbox key={t} value={t} label={PERMISSION_WORDS[t] ?? t} size="xs" />
              ))}
            </div>
          </Checkbox.Group>
        </Section>
      ))}
    </EditorDrawer>
  );
}

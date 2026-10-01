import { useEffect, useRef, useState } from 'react';
import { ActionIcon, Button, Checkbox, Group, Text, TextInput } from '@mantine/core';
import { IconX } from '@tabler/icons-react';

import type { ConfigCatalogueView, ConfigDeclarationView, ConfigSecuritySettingView } from './api.ts';
import { keyTaken, removeItem, upsertSecuritySetting } from './document.ts';
import { Section } from '../../ui/Section.tsx';
import { EditorDrawer, MATCH_HINT } from './EditorDrawer.tsx';
import classes from './Configuration.module.css';
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

/** roles × permissions as the grid holds it. */
type Grid = Record<string, Set<string>>;

function toGrid(item: ConfigSecuritySettingView | null): { roles: string[]; grid: Grid } {
  const grid: Grid = {};
  const roles: string[] = [];
  if (item) {
    for (const [permission, holders] of Object.entries(item.permissions)) {
      for (const role of holders) {
        if (!roles.includes(role)) roles.push(role);
        grid[role] ??= new Set();
        grid[role].add(permission);
      }
    }
  }
  return { roles, grid };
}

function toWire(roles: string[], grid: Grid, types: string[]): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const type of types) {
    const holders = roles.filter((r) => grid[r]?.has(type));
    if (holders.length > 0) out[type] = holders;
  }
  return out;
}

interface Errors {
  match?: string;
  roles?: string;
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
  const initial = toGrid(item);
  const [match, setMatch] = useState(item?.match ?? '');
  const [roles, setRoles] = useState<string[]>(initial.roles);
  const [grid, setGrid] = useState<Grid>(initial.grid);
  const [newRole, setNewRole] = useState('');
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const matchRef = useRef<HTMLInputElement>(null);
  const roleRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!opened) return;
    const g = toGrid(item);
    setMatch(item?.match ?? '');
    setRoles(g.roles);
    setGrid(g.grid);
    setNewRole('');
    setTouched({});
    setSubmitted(false);
  }, [opened, item]);

  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);
  const types = catalogue.permissionTypes;

  const validate = (): Errors => {
    const errors: Errors = {};
    const m = match.trim();
    if (!m) errors.match = 'A match pattern is required — it names the addresses these roles apply to.';
    else if (keyTaken(declaration.document.securitySettings, (i) => i.match, m, item?.match)) {
      errors.match = `"${m}" is already declared. Edit that entry instead.`;
    }
    if (roles.length === 0) errors.roles = 'Add at least one role. A match with no roles grants nothing to anyone.';
    return errors;
  };
  const errors = validate();
  const errorFor = (field: keyof Errors) => (touched[field] || submitted ? errors[field] : undefined);

  const addRole = () => {
    const name = newRole.trim();
    if (!name || roles.includes(name)) return;
    setRoles((r) => [...r, name]);
    setGrid((g) => ({ ...g, [name]: new Set() }));
    setNewRole('');
  };

  const dropRole = (role: string) => {
    setRoles((r) => r.filter((x) => x !== role));
    setGrid((g) => {
      const next = { ...g };
      delete next[role];
      return next;
    });
  };

  const hold = (role: string, held: string[]) => setGrid((g) => ({ ...g, [role]: new Set(held) }));

  const submit = () => {
    setSubmitted(true);
    if (errors.match) {
      matchRef.current?.focus();
      return;
    }
    if (errors.roles) {
      roleRef.current?.focus();
      return;
    }
    const next: ConfigSecuritySettingView = { match: match.trim(), permissions: toWire(roles, grid, types) };
    save(
      upsertSecuritySetting(declaration.document, next, item?.match),
      `${item ? 'Edited' : 'Added'} security setting ${next.match}`,
    );
  };

  const remove = () =>
    save(removeItem(declaration.document, 'securitySettings', item!.match), `Removed security setting ${item!.match}`);

  const broad = match.trim() === '#' || match.trim() === '*';

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
      hint={submitted && Object.keys(errors).length > 0 ? 'Fix the fields above to continue.' : undefined}
      secondary={
        item ? (
          <Button variant="subtle" size="xs" onClick={remove} loading={isPending}>
            Remove from declaration
          </Button>
        ) : null
      }
    >
      <TextInput
        ref={matchRef}
        label="Match pattern"
        description={
          broad
            ? `${MATCH_HINT} This match covers every address, including the management address — applying it is a High hazard because it can revoke Studio's own access.`
            : MATCH_HINT
        }
        value={match}
        onChange={(e) => setMatch(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, match: true }))}
        error={errorFor('match')}
        required
      />

      <Group align="flex-end" gap="xs">
        <TextInput
          ref={roleRef}
          label="Add a role"
          description="The role name as the broker's login module reports it."
          value={newRole}
          onChange={(e) => setNewRole(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              addRole();
            }
          }}
          error={errorFor('roles')}
          flex={1}
        />
        <Button variant="default" size="xs" onClick={addRole}>
          Add role
        </Button>
      </Group>

      <Text size="sm" c="dimmed">
        <b>view</b> and <b>edit</b> are sent to the broker but it does not report them back over management (measured on
        2.44), so the plan, the verification and the drift check cannot see them. Confirm those two in the broker's own
        configuration.
      </Text>

      {roles.map((role) => {
        const held = types.filter((t) => grid[role]?.has(t));
        return (
          <Section
            key={role}
            variant="card"
            headingLevel={3}
            title={role}
            description={
              held.length === 0 ? 'may do nothing here' : `may ${held.map((t) => PERMISSION_WORDS[t] ?? t).join(', ')}`
            }
            actions={
              <ActionIcon variant="subtle" size="sm" aria-label={`Remove role ${role}`} onClick={() => dropRole(role)}>
                <IconX size="0.875rem" />
              </ActionIcon>
            }
          >
            <Checkbox.Group label={`What ${role} may do`} value={held} onChange={(v) => hold(role, v)}>
              <div className={classes.permissions}>
                {types.map((t) => (
                  <Checkbox key={t} value={t} label={PERMISSION_WORDS[t] ?? t} size="xs" />
                ))}
              </div>
            </Checkbox.Group>
          </Section>
        );
      })}
    </EditorDrawer>
  );
}

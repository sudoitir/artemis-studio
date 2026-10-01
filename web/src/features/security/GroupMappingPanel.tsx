import { useRef, useState } from 'react';
import { ActionIcon, Button, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';

import { useAuthProviders } from '../../kernel/auth/api.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable } from '../../ui/table/index.ts';
import { useCreateGroupMapping, useDeleteGroupMapping, useGroupMappings, useRoles, useSetDefaultRole } from './api.ts';
import type { GroupMappingView } from './api.ts';
import { mappingColumns } from './columns.ts';
import { withNotice } from './outcomes.ts';
import classes from './Security.module.css';

const ADD: ActionVerb = { verb: 'Add', past: 'Added', progressive: 'Adding' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };
const SET_DEFAULT: ActionVerb = { verb: 'Set', past: 'Set', progressive: 'Setting' };

const rowKey = (m: GroupMappingView) => m.id;

/** Group -> role mappings for each external identity provider, re-applied on every sign-in (ADR-0073). */
export function GroupMappingPanel() {
  return (
    <Section
      title="Group mappings"
      description="Which identity provider groups grant which role. Applied again every time a user signs in."
    >
      <Providers />
    </Section>
  );
}

function Providers() {
  const providers = useAuthProviders();
  const external = (providers.data ?? []).filter((p) => p.id !== 'local');
  const [picked, setPicked] = useState<string | null>(null);
  const providerId = picked ?? external[0]?.id ?? null;

  if (providers.isError) {
    return <ErrorState error={providers.error} onRetry={() => void providers.refetch()} />;
  }
  if (providers.isPending) {
    return <LoadingState label="Loading identity providers" blockSize="12rem" />;
  }
  if (external.length === 0) {
    return (
      <EmptyState
        kind="empty"
        title="No external identity provider"
        description={
          <>
            There are no groups to map. An OpenID Connect provider appears here once{' '}
            <span className={classes.code}>spring.security.oauth2.client.registration.*</span> is set, and so does the
            sign-in of an installed plugin.
          </>
        }
      />
    );
  }

  return (
    <Stack gap="md">
      <Select
        label="Identity provider"
        data={external.map((p) => ({ value: p.id, label: p.label }))}
        value={providerId}
        onChange={setPicked}
        allowDeselect={false}
        className={classes.field}
      />
      {providerId ? <ProviderMappings key={providerId} providerId={providerId} /> : null}
    </Stack>
  );
}

function ProviderMappings({ providerId }: Readonly<{ providerId: string }>) {
  const mappings = useGroupMappings(providerId);
  const roles = useRoles();
  const setDefault = useSetDefaultRole(providerId);

  const [adding, setAdding] = useState(false);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [deleting, setDeleting] = useState<GroupMappingView | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const roleOptions = (roles.data ?? []).map((r) => ({ value: r.id, label: r.name }));

  const columns = mappingColumns({
    actions: (m) => (
      <ActionIcon
        variant="subtle"
        onClick={() => {
          setDeleting(m);
          setDeleteOpen(true);
        }}
        aria-label={`Delete mapping for ${m.groupName}`}
      >
        <IconTrash size="1rem" aria-hidden />
      </ActionIcon>
    ),
  });

  return (
    <Stack gap="md">
      <Select
        label="Default role"
        description="Granted when a user's groups match no mapping. With none, that user is refused sign-in."
        data={roleOptions}
        value={mappings.data?.defaultRoleId ?? null}
        disabled={setDefault.isPending}
        onChange={(value) =>
          setDefault.mutate(
            { roleId: value },
            withNotice(SET_DEFAULT, 'the default role', 'The default role is unchanged. Try again.'),
          )
        }
        placeholder="None: refuse unmapped users"
        clearable
        className={classes.field}
      />

      <Text size="sm" c="dimmed">
        Applied to a user&apos;s grants every time they sign in through this provider.
      </Text>

      <DataTable
        variant="static"
        label="Group mappings"
        storageKey="security.group-mappings"
        columns={columns}
        data={mappings.data?.mappings ?? []}
        rowKey={rowKey}
        loading={mappings.isPending}
        error={
          mappings.isError ? <ErrorState error={mappings.error} onRetry={() => void mappings.refetch()} /> : undefined
        }
        toolbar={{ start: <Button onClick={() => setAdding(true)}>New mapping</Button> }}
        empty={
          <EmptyState
            kind="empty"
            title="No group mappings"
            description="A mapping grants a role to everyone in an identity provider group when they sign in. Users in no mapped group get the default role."
          />
        }
      />

      <NewMappingModal
        providerId={providerId}
        opened={adding}
        onClose={() => setAdding(false)}
        roleOptions={roleOptions}
      />
      <DeleteMapping
        providerId={providerId}
        mapping={deleting}
        opened={deleteOpen}
        onClose={() => setDeleteOpen(false)}
      />
    </Stack>
  );
}

/** States what removing a mapping does to the next sign-ins before it can be armed, then asks for the group. */
function DeleteMapping({
  providerId,
  mapping,
  opened,
  onClose,
}: Readonly<{ providerId: string; mapping: GroupMappingView | null; opened: boolean; onClose: () => void }>) {
  const remove = useDeleteGroupMapping(providerId);
  const confirm = (m: GroupMappingView) =>
    remove.mutate(
      m.id,
      withNotice(DELETE, `the mapping for ${m.groupName}`, 'The mapping still applies. Try again.', onClose),
    );

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={mapping ? `Delete the mapping for ${mapping.groupName}` : 'Delete mapping'}
      tone="danger"
      typedName={mapping?.groupName}
      pending={remove.isPending}
      confirmLabel="Delete mapping"
      consequence={
        mapping
          ? `Members of ${mapping.groupName} no longer get the role ${mapping.roleName} when they sign in. It is dropped from their grants at their next sign-in.`
          : ''
      }
      onConfirm={() => mapping && confirm(mapping)}
    />
  );
}

function NewMappingModal({
  providerId,
  opened,
  onClose,
  roleOptions,
}: Readonly<{
  providerId: string;
  opened: boolean;
  onClose: () => void;
  roleOptions: { value: string; label: string }[];
}>) {
  const create = useCreateGroupMapping(providerId);
  const [groupName, setGroupName] = useState('');
  const [roleId, setRoleId] = useState<string | null>(null);
  const [groupError, setGroupError] = useState<string | null>(null);
  const [roleError, setRoleError] = useState<string | null>(null);
  const groupInput = useRef<HTMLInputElement>(null);
  const roleInput = useRef<HTMLInputElement>(null);

  const close = () => {
    onClose();
    setGroupError(null);
    setRoleError(null);
  };

  const submit = () => {
    const groupMissing = !groupName.trim();
    setGroupError(groupMissing ? 'Enter the group name exactly as the provider sends it.' : null);
    setRoleError(roleId ? null : 'Choose the role the group grants.');
    if (groupMissing) {
      groupInput.current?.focus();
      return;
    }
    if (!roleId) {
      roleInput.current?.focus();
      return;
    }
    const subject = `the mapping for ${groupName.trim()}`;
    create.mutate(
      { groupName: groupName.trim(), roleId, scopeType: 'GLOBAL' },
      {
        onSuccess: () => {
          notify.succeeded({ action: ADD, subject });
          close();
          setGroupName('');
          setRoleId(null);
        },
        onError: (error) =>
          notify.failed({ action: ADD, subject, cause: error.message, next: 'No mapping was added. Try again.' }),
      },
    );
  };

  return (
    <Modal opened={opened} onClose={close} title="New group mapping">
      <Stack gap="sm">
        <TextInput
          ref={groupInput}
          label="Group"
          value={groupName}
          onChange={(e) => setGroupName(e.currentTarget.value)}
          onBlur={() =>
            setGroupError(groupName.trim() ? null : 'Enter the group name exactly as the provider sends it.')
          }
          error={groupError}
          required
        />
        <Select
          ref={roleInput}
          label="Role"
          data={roleOptions}
          value={roleId}
          onChange={(value) => {
            setRoleId(value);
            if (value) setRoleError(null);
          }}
          error={roleError}
          required
        />
        <Button loading={create.isPending} onClick={submit}>
          Add mapping
        </Button>
      </Stack>
    </Modal>
  );
}

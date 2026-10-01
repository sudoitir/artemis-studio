import { useCallback, useMemo, useRef, useState } from 'react';
import { Button, ColorInput, Modal, NumberInput, Stack, TextInput } from '@mantine/core';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import {
  useCreateEnvironment,
  useDeleteEnvironment,
  useEnvironments,
  useUpdateEnvironment,
  type EnvironmentView,
} from './api.ts';
import classes from './Clusters.module.css';
import { environmentColumns } from './environmentColumns.tsx';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };
const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

/** Environment grouping CRUD (environments spec). Cluster assignment happens from the cluster's own settings. */
export function EnvironmentsPanel() {
  const environments = useEnvironments();
  const create = useCreateEnvironment();
  const update = useUpdateEnvironment();
  const remove = useDeleteEnvironment();

  const [editing, setEditing] = useState<EnvironmentView | 'new' | null>(null);
  const [deleting, setDeleting] = useState<EnvironmentView | null>(null);
  const [name, setName] = useState('');
  const [colour, setColour] = useState('');
  const [sortOrder, setSortOrder] = useState(0);
  const [nameError, setNameError] = useState<string | undefined>();
  const nameRef = useRef<HTMLInputElement>(null);

  const rows = environments.data ?? [];

  const { reset: resetCreate } = create;
  const { reset: resetUpdate } = update;
  const { reset: resetRemove } = remove;
  const rowCount = rows.length;

  const openNew = useCallback(() => {
    resetCreate();
    setEditing('new');
    setName('');
    setColour('');
    setSortOrder(rowCount);
    setNameError(undefined);
  }, [resetCreate, rowCount]);

  const openEdit = useCallback(
    (env: EnvironmentView) => {
      resetUpdate();
      setEditing(env);
      setName(env.name);
      setColour(env.colour ?? '');
      setSortOrder(env.sortOrder);
      setNameError(undefined);
    },
    [resetUpdate],
  );

  const askDelete = useCallback(
    (env: EnvironmentView) => {
      resetRemove();
      setDeleting(env);
    },
    [resetRemove],
  );

  // Stable, so the table does not measure its columns again on every render.
  const columns = useMemo(() => environmentColumns(openEdit, askDelete), [openEdit, askDelete]);

  function save() {
    if (!name.trim()) {
      setNameError('Give the environment a name.');
      nameRef.current?.focus();
      return;
    }
    const body = { name: name.trim(), colour: colour || null, sortOrder };
    const subject = `environment ${body.name}`;
    if (editing === 'new') {
      create.mutate(body, {
        onSuccess: () => {
          notify.succeeded({ action: CREATE, subject });
          setEditing(null);
        },
      });
    } else if (editing) {
      update.mutate(
        { environmentId: editing.id, body },
        {
          onSuccess: () => {
            notify.succeeded({ action: SAVE, subject });
            setEditing(null);
          },
        },
      );
    }
  }

  const failure = editing === 'new' ? create.error : update.error;

  return (
    <Section
      title="Environments"
      description={`${rows.length} environment${rows.length === 1 ? '' : 's'}. An environment groups clusters, such as production or staging.`}
      actions={
        <Button size="xs" onClick={openNew}>
          New environment
        </Button>
      }
    >
      <DataTable
        variant="static"
        label="Environments"
        storageKey="clusters.environments"
        columns={columns}
        data={rows}
        rowKey={(e) => e.id}
        height={{ maxRows: 12 }}
        loading={environments.isPending}
        error={
          environments.isError ? (
            <ErrorState error={environments.error} onRetry={() => void environments.refetch()} />
          ) : undefined
        }
        empty={
          <EmptyState
            kind="empty"
            title="No environments yet"
            description="An environment groups clusters, such as production or staging, and marks them in the cluster list. Create one, then assign a cluster to it from the cluster's own settings."
            action={
              <Button size="xs" onClick={openNew}>
                New environment
              </Button>
            }
          />
        }
      />

      <Modal
        opened={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? 'New environment' : `Edit "${editing === null ? '' : editing.name}"`}
      >
        <form
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <Stack gap="sm">
            <TextInput
              ref={nameRef}
              label="Name"
              value={name}
              error={nameError}
              onChange={(e) => {
                setName(e.currentTarget.value);
                setNameError(undefined);
              }}
              onBlur={() => setNameError(name.trim() ? undefined : 'Give the environment a name.')}
              data-autofocus
              required
            />
            <ColorInput
              label="Colour"
              description="Optional. Marks the environment beside its name in the cluster list."
              value={colour}
              onChange={setColour}
              format="hex"
              closeOnColorSwatchClick
            />
            <NumberInput
              label="Sort order"
              description="Environments are listed from the lowest number up."
              value={sortOrder}
              onChange={(v) => setSortOrder(Number(v) || 0)}
            />
            {failure ? <ErrorState variant="inline" error={failure} /> : null}
            <Button type="submit" loading={create.isPending || update.isPending} className={classes.start}>
              Save
            </Button>
          </Stack>
        </form>
      </Modal>

      <ConfirmDialog
        opened={deleting !== null}
        onClose={() => setDeleting(null)}
        title="Delete environment"
        consequence={
          <Stack gap="xs">
            <span>
              Deleting <strong>{deleting?.name}</strong> removes the grouping. Its clusters stay registered and have no
              environment afterwards; permission grants scoped to this environment are removed with it.
            </span>
            {remove.isError ? <ErrorState variant="inline" error={remove.error} /> : null}
          </Stack>
        }
        confirmLabel="Delete environment"
        tone="danger"
        typedName={deleting?.name}
        pending={remove.isPending}
        onConfirm={() =>
          deleting &&
          remove.mutate(deleting.id, {
            onSuccess: () => {
              notify.succeeded({ action: DELETE, subject: `environment ${deleting.name}` });
              setDeleting(null);
            },
          })
        }
      />
    </Section>
  );
}

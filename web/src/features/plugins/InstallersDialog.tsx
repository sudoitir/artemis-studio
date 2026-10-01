import { useMemo, useState } from 'react';
import { Button, Modal, Stack, Text, TextInput } from '@mantine/core';

import { needsReauthentication } from '../../kernel/auth/api.ts';
import { StepUp } from '../../kernel/auth/StepUp.tsx';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable } from '../../ui/table/index.ts';
import { useGrantInstaller, useInstallers, useRevokeInstaller } from './api.ts';
import { installerColumns } from './dialogColumns.tsx';
import { Refusal } from './Notice.tsx';
import styles from './Plugins.module.css';

const ADD: ActionVerb = { verb: 'Add', past: 'Added', progressive: 'Adding' };
const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };

/**
 * Who can install plugins (ADR-0103). Deliberately not a role: only an installer changes this,
 * after confirming it is them, and the last one cannot be removed.
 */
export function InstallersDialog({ opened, onClose }: Readonly<{ opened: boolean; onClose: () => void }>) {
  useDisplayZone();
  const installers = useInstallers(opened);
  const grant = useGrantInstaller();
  const revoke = useRevokeInstaller();
  const [username, setUsername] = useState('');
  const [empty, setEmpty] = useState(false);
  const error = grant.error ?? revoke.error;
  const stale = needsReauthentication(error);
  const only = (installers.data ?? []).length <= 1;

  // Only what the columns read, so the table does not measure them again on every render.
  const { mutate: revokeInstaller } = revoke;
  const removing = revoke.isPending ? revoke.variables : undefined;
  const installerRows = installers.data;
  const columns = useMemo(
    () =>
      installerColumns({
        only,
        removing,
        onRemove: (userId) => {
          const who = installerRows?.find((i) => i.userId === userId)?.username ?? 'the installer';
          revokeInstaller(userId, {
            onSuccess: () => notify.succeeded({ action: REMOVE, subject: `installer ${who}` }),
          });
        },
      }),
    [only, removing, installerRows, revokeInstaller],
  );

  return (
    <Modal opened={opened} onClose={onClose} title="Who can install plugins" size="lg">
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Installing a plugin runs its code inside Studio, so this is kept apart from roles: no role, however broad,
          lets anyone install. Changes take effect on the person's next request.
        </Text>
        <DataTable
          variant="static"
          label="Installers"
          columns={columns}
          data={installers.data ?? []}
          rowKey={(i) => i.userId}
          storageKey="plugins.installers"
          height={{ maxRows: 8 }}
          loading={installers.isPending}
          error={
            installers.isError ? (
              <ErrorState error={installers.error} onRetry={() => void installers.refetch()} />
            ) : undefined
          }
          empty={
            <EmptyState
              kind="empty"
              title="No installer yet"
              description="An installer is a user who may install, update and remove plugins. Add one below."
            />
          }
        />
        {only ? (
          <Text size="xs" c="dimmed">
            The only installer cannot be removed; add another first.
          </Text>
        ) : null}
        <StepUp returnTo={`${globalThis.location.pathname}?tab=plugins`} />
        {error && !stale ? <Refusal error={error} /> : null}
        <form
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            if (!username.trim()) {
              setEmpty(true);
              return;
            }
            const name = username.trim();
            grant.mutate(name, {
              onSuccess: () => {
                setUsername('');
                notify.succeeded({ action: ADD, subject: `installer ${name}` });
              },
            });
          }}
        >
          <div className={styles.formRow}>
            <TextInput
              label="Add an installer"
              description="Their Studio username"
              value={username}
              onChange={(e) => {
                setUsername(e.currentTarget.value);
                setEmpty(false);
              }}
              error={empty ? 'Enter a username.' : undefined}
              className={styles.field}
            />
            <Button type="submit" loading={grant.isPending}>
              Add
            </Button>
          </div>
        </form>
      </Stack>
    </Modal>
  );
}

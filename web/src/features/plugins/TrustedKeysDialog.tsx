import { useMemo, useState } from 'react';
import { Button, Modal, Stack, Switch, Text, TextInput, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';

import { needsReauthentication } from '../../kernel/auth/api.ts';
import { StepUp } from '../../kernel/auth/StepUp.tsx';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable } from '../../ui/table/index.ts';
import { useAddKey, useRemoveKey, useTrustedKeys, useTrustPolicy, type TrustedKeyView } from './api.ts';
import { ConfirmAction } from './ConfirmAction.tsx';
import { keyColumns } from './dialogColumns.tsx';
import { Refusal } from './Refusal.tsx';
import styles from './Plugins.module.css';

const TRUST: ActionVerb = { verb: 'Trust', past: 'Trusted', progressive: 'Trusting' };
const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };
const ALLOW: ActionVerb = { verb: 'Change', past: 'Changed', progressive: 'Changing' };

/**
 * Whose signatures Studio accepts on plugins (ADR-0140), and the one switch that lets unverified
 * plugins in anyway. Only an installer with a fresh sign-in changes either; the server enforces it.
 */
export function TrustedKeysDialog({ opened, onClose }: Readonly<{ opened: boolean; onClose: () => void }>) {
  const trusted = useTrustedKeys(opened);
  const add = useAddKey();
  const remove = useRemoveKey();
  const policy = useTrustPolicy();
  const form = useForm({
    initialValues: { name: '', pem: '' },
    validateInputOnBlur: true,
    validate: {
      name: (v) => (v.trim() ? null : 'Give the key a name, such as the publisher.'),
      pem: (v) => (v.trim() ? null : 'Paste a certificate or public key in PEM form.'),
    },
  });
  const [removing, setRemoving] = useState<TrustedKeyView | null>(null);

  const data = trusted.data;
  useDisplayZone();
  const error = add.error ?? policy.error;
  const refused = error !== null && !needsReauthentication(error);
  const allow = policy.isPending ? policy.variables : data?.allowUnverified;
  const becomingUnverified = removing ? (data?.signedPlugins[removing.fingerprint] ?? []) : [];
  const columns = useMemo(
    () =>
      keyColumns({
        signedPlugins: data?.signedPlugins ?? {},
        onRemove: (key) => {
          remove.reset();
          setRemoving(key);
        },
      }),
    [data?.signedPlugins, remove],
  );

  const submit = form.onSubmit(({ name, pem }) => {
    add.mutate(
      { name: name.trim(), pem: pem.trim() },
      {
        onSuccess: (key) => {
          notify.succeeded({ action: TRUST, subject: `key ${key.name}` });
          form.reset();
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      // One Escape closes one layer: while the removal is open, it closes that, not this.
      closeOnEscape={removing === null}
      title="Trusted keys"
      size="xl"
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          A plugin is verified when it is signed by one of these keys. Removing a key marks every plugin it signed as
          unverified at once, and blocks their updates unless a trusted key signs them.
        </Text>
        <StepUp returnTo={`${globalThis.location.pathname}?tab=plugins`} />
        <DataTable
          variant="static"
          label="Trusted keys"
          columns={columns}
          data={data?.keys ?? []}
          rowKey={(k) => k.fingerprint}
          storageKey="plugins.keys"
          height={{ maxRows: 8 }}
          loading={trusted.isPending}
          error={
            trusted.isError ? <ErrorState error={trusted.error} onRetry={() => void trusted.refetch()} /> : undefined
          }
          empty={
            <EmptyState
              kind="empty"
              title="No key is trusted yet"
              description="Only unverified plugins can be installed, and only while they are allowed below. Trust a publisher's key from a plugin's review, or paste one here."
            />
          }
        />
        {refused ? <Refusal error={error} /> : null}
        <form noValidate onSubmit={submit}>
          <Stack gap="xs">
            <TextInput
              label="Key name"
              description="How it is listed here, such as the publisher"
              {...form.getInputProps('name')}
              autoComplete="off"
              className={styles.field}
            />
            <Textarea
              label="Certificate or public key (PEM)"
              description="The publisher's certificate, exported with keytool -exportcert -rfc, or their public key"
              {...form.getInputProps('pem')}
              autosize
              minRows={4}
              maxRows={10}
              classNames={{ input: styles.pem }}
            />
            <Button type="submit" loading={add.isPending} className={styles.start}>
              Add key
            </Button>
          </Stack>
        </form>
        {data ? (
          <Stack gap="xs">
            <Switch
              label="Allow unverified plugins"
              description="Unsigned plugins, and plugins signed by a key that is not listed above, can then be installed."
              checked={!!allow}
              disabled={policy.isPending}
              onChange={(e) =>
                policy.mutate(e.currentTarget.checked, {
                  onSuccess: () => notify.succeeded({ action: ALLOW, subject: 'the policy on unverified plugins' }),
                })
              }
            />
            <Text size="sm" className={styles.danger}>
              Danger: an unverified plugin runs code inside Studio with its access to your brokers and database, and
              nobody you trust vouches for it. Each such install is audited and shown with an Unverified badge. Leave
              this off unless you build the plugins yourself.
            </Text>
          </Stack>
        ) : null}
      </Stack>

      <ConfirmAction
        opened={removing !== null}
        onClose={() => setRemoving(null)}
        title={`Remove ${removing?.name ?? 'key'}`}
        confirmLabel={`Remove ${removing?.name ?? 'key'}`}
        danger
        pending={remove.isPending}
        error={remove.error}
        onConfirm={() =>
          removing &&
          remove.mutate(removing.fingerprint, {
            onSuccess: () => {
              notify.succeeded({ action: REMOVE, subject: `key ${removing.name}` });
              setRemoving(null);
            },
          })
        }
      >
        <Stack gap="xs">
          <Text size="sm">
            Plugins signed by this key stop being verified as soon as you confirm, and their next update is refused
            unless a trusted key signs it.
          </Text>
          <Text size="sm">
            {becomingUnverified.length > 0
              ? `These plugins become unverified: ${becomingUnverified.join(', ')}. They keep running.`
              : 'No installed plugin was signed by it.'}
          </Text>
        </Stack>
      </ConfirmAction>
    </Modal>
  );
}

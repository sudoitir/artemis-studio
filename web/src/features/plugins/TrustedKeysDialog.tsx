import { useState } from 'react';
import {
  Alert,
  Button,
  Code,
  Group,
  Loader,
  Modal,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';

import { useAddKey, useRemoveKey, useTrustedKeys, useTrustPolicy, violationsOf, type TrustedKeyView } from './api.ts';
import { ConfirmAction } from './ConfirmAction.tsx';
import styles from './Plugins.module.css';
import { needsReauthentication } from '../../kernel/auth/api.ts';
import { StepUp } from '../../kernel/auth/StepUp.tsx';

/**
 * Whose signatures Studio accepts on plugins (ADR-0140), and the one switch that lets unverified
 * plugins in anyway. Only an installer with a fresh sign-in changes either; the server enforces it.
 */
export function TrustedKeysDialog({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const trusted = useTrustedKeys(opened);
  const add = useAddKey();
  const remove = useRemoveKey();
  const policy = useTrustPolicy();
  const [name, setName] = useState('');
  const [pem, setPem] = useState('');
  const [invalid, setInvalid] = useState<{ name?: string; pem?: string }>({});
  const [removing, setRemoving] = useState<TrustedKeyView | null>(null);
  const [notice, setNotice] = useState('');

  const data = trusted.data;
  const error = add.error ?? policy.error;
  const refusal =
    error && !needsReauthentication(error)
      ? violationsOf(error)
          .map((v) => v.message)
          .join(' ') || error.message
      : null;
  const allow = policy.isPending ? policy.variables : data?.allowUnverified;
  const becomingUnverified = removing ? (data?.signedPlugins[removing.fingerprint] ?? []) : [];

  const submit = () => {
    const problems = {
      name: name.trim() ? undefined : 'Give the key a name, such as the publisher.',
      pem: pem.trim() ? undefined : 'Paste a certificate or public key in PEM form.',
    };
    setInvalid(problems);
    if (problems.name || problems.pem) return;
    add.mutate(
      { name: name.trim(), pem: pem.trim() },
      {
        onSuccess: (key) => {
          setNotice(`Trusted ${key.name}.`);
          setName('');
          setPem('');
        },
      },
    );
  };

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
        <StepUp returnTo={`${window.location.pathname}?tab=plugins`} />
        {trusted.isPending ? <Loader size="sm" /> : null}
        {trusted.isError ? (
          <Alert color="red" variant="light" role="alert" title="Trusted keys could not be listed">
            {trusted.error.message}
          </Alert>
        ) : null}
        {data && data.keys.length === 0 ? (
          <Text size="sm">
            No key is trusted yet, so only unverified plugins can be installed, and only while they are allowed below.
            Trust a publisher's key from a plugin's review, or paste one here.
          </Text>
        ) : null}
        {data && data.keys.length > 0 ? (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Fingerprint</Table.Th>
                <Table.Th>Signed plugins</Table.Th>
                <Table.Th>Added</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.keys.map((k) => (
                <Table.Tr key={k.fingerprint}>
                  <Table.Td>
                    {k.name}
                    <Text size="xs" c="dimmed">
                      {k.subject}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Code className={styles.fingerprint}>{k.fingerprint}</Code>
                  </Table.Td>
                  <Table.Td>{(data.signedPlugins[k.fingerprint] ?? []).join(', ') || 'None installed'}</Table.Td>
                  <Table.Td>
                    {new Date(k.addedAt).toLocaleDateString()} by {k.addedBy}
                  </Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="subtle"
                      color="red"
                      aria-label={`Remove ${k.name}`}
                      onClick={() => {
                        remove.reset();
                        setRemoving(k);
                      }}
                    >
                      Remove
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        ) : null}
        {notice ? (
          <Text size="sm" role="status">
            {notice}
          </Text>
        ) : null}
        {refusal ? (
          <Alert color="red" variant="light" role="alert" title="Not done">
            {refusal}
          </Alert>
        ) : null}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <Stack gap="xs">
            <TextInput
              label="Key name"
              description="How it is listed here, such as the publisher"
              value={name}
              onChange={(e) => setName(e.currentTarget.value)}
              error={invalid.name}
              autoComplete="off"
              w={320}
            />
            <Textarea
              label="Certificate or public key (PEM)"
              description="The publisher's certificate, exported with keytool -exportcert -rfc, or their public key"
              value={pem}
              onChange={(e) => setPem(e.currentTarget.value)}
              error={invalid.pem}
              autosize
              minRows={4}
              maxRows={10}
              styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
            />
            <Group>
              <Button type="submit" loading={add.isPending}>
                Add key
              </Button>
            </Group>
          </Stack>
        </form>
        {data ? (
          <Stack gap={4}>
            <Switch
              label="Allow unverified plugins"
              description="Unsigned plugins, and plugins signed by a key that is not listed above, can then be installed."
              checked={!!allow}
              disabled={policy.isPending}
              onChange={(e) => policy.mutate(e.currentTarget.checked)}
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
              setNotice(`Removed ${removing.name}.`);
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

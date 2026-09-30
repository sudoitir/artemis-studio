import { useState } from 'react';
import { Checkbox, Code, Stack, Text, TextInput } from '@mantine/core';

import { ConfirmAction } from './ConfirmAction.tsx';
import { useAddKey, type PluginTrustView } from './api.ts';
import styles from './Plugins.module.css';

/**
 * Trust the key that signed an upload (ADR-0140). The server takes the key from the stored jar, so
 * what is confirmed here cannot be swapped for another; the installer confirms they compared the
 * fingerprint with the one the publisher publishes. Validated on activation, not by a dead button.
 */
export function TrustKeyDialog({
  opened,
  onClose,
  sha256,
  trust,
  returnTo,
  onTrusted,
}: Readonly<{
  opened: boolean;
  onClose: () => void;
  sha256: string;
  trust: PluginTrustView;
  returnTo: string;
  onTrusted: () => void;
}>) {
  const add = useAddKey();
  const [name, setName] = useState('');
  const [compared, setCompared] = useState(false);
  const [invalid, setInvalid] = useState<{ name?: string; compared?: string }>({});

  const submit = () => {
    const problems = {
      name: name.trim() ? undefined : 'Give the key a name, such as the publisher.',
      compared: compared ? undefined : 'Confirm you compared the fingerprint.',
    };
    setInvalid(problems);
    if (problems.name || problems.compared) return;
    add.mutate(
      { name: name.trim(), upload: sha256 },
      {
        onSuccess: () => {
          setName('');
          setCompared(false);
          onTrusted();
          onClose();
        },
      },
    );
  };

  return (
    <ConfirmAction
      opened={opened}
      onClose={onClose}
      title="Trust this publisher's key"
      confirmLabel="Trust this key"
      pending={add.isPending}
      error={add.error}
      returnTo={returnTo}
      onConfirm={submit}
    >
      <Stack gap="sm">
        <Text size="sm">
          Every plugin signed with this key will be treated as verified, including future versions, until you remove the
          key under Trusted keys.
        </Text>
        <Stack gap={2}>
          <Text size="sm">Fingerprint</Text>
          <Code className={styles.fingerprint}>{trust.fingerprint}</Code>
          <Text size="sm">Certificate subject: {trust.subject ?? 'none'}</Text>
        </Stack>
        <TextInput
          label="Key name"
          description="How it is listed under Trusted keys"
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
          onBlur={() => setInvalid((p) => ({ ...p, name: name.trim() ? undefined : 'Give the key a name.' }))}
          error={invalid.name}
          data-autofocus
          autoComplete="off"
        />
        <Checkbox
          checked={compared}
          onChange={(e) => setCompared(e.currentTarget.checked)}
          label="I compared this fingerprint with the one the publisher publishes"
          error={invalid.compared}
        />
      </Stack>
    </ConfirmAction>
  );
}

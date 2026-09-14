import { useState } from 'react';
import { Button, PasswordInput, SegmentedControl, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { useCluster, useRotateCredentials } from './api.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import { RegisterClusterButton } from './RegisterCluster.tsx';

/** Settings section: register another cluster. */
export function RegisterSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Register another cluster, or manage this one from its header (Check rediscovers, Remove
        needs its typed name).
      </Text>
      <RegisterClusterButton />
    </>
  );
}

type CredentialKind = 'JOLOKIA_BASIC' | 'CORE';

/**
 * Studio holds two broker accounts per cluster: the HTTP Basic one Jolokia
 * management uses, and an optional Core-protocol one. When no Core credential is
 * stored, the Core client falls back to the Jolokia account (ADR-0026, D6) — which
 * fails with `AMQ229099` on a cluster whose management account is also its
 * `<cluster-user>`, because Artemis reserves that account for inter-node traffic.
 * Setting a Core credential here is the fix, and it is why the kind is selectable
 * rather than implied.
 */
const CREDENTIAL_KINDS: Record<CredentialKind, { label: string; hint: string }> = {
  JOLOKIA_BASIC: {
    label: 'Management (Jolokia)',
    hint: 'The HTTP Basic account Studio uses to reach every node’s management endpoint. Also used for the Core protocol when no Core account is stored.',
  },
  CORE: {
    label: 'Core protocol',
    hint: 'A separate broker account for the Core connection (notifications and faithful message I/O). Set this when the management account is the broker’s <cluster-user>, which Artemis refuses to authenticate over Core with AMQ229099.',
  },
};

function CredentialRotation({ clusterId, clusterName }: { clusterId: string; clusterName: string }) {
  const rotate = useRotateCredentials(clusterId);
  const [kind, setKind] = useState<CredentialKind>('JOLOKIA_BASIC');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const armed = confirm === clusterName && username.length > 0 && password.length > 0;

  return (
    <Stack gap="xs" maw={480}>
      <div>
        <Text component="label" size="xs" fw={500} display="block" mb={4}>
          Account
        </Text>
        <SegmentedControl
          size="xs"
          fullWidth
          value={kind}
          onChange={(v) => setKind(v as CredentialKind)}
          data={(Object.keys(CREDENTIAL_KINDS) as CredentialKind[]).map((k) => ({
            value: k,
            label: CREDENTIAL_KINDS[k].label,
          }))}
        />
        <Text size="xs" c="dimmed" mt={4}>
          {CREDENTIAL_KINDS[kind].hint}
        </Text>
      </div>
      <TextInput
        label="Username"
        value={username}
        onChange={(e) => setUsername(e.currentTarget.value)}
        size="xs"
      />
      <PasswordInput
        label="Password"
        value={password}
        onChange={(e) => setPassword(e.currentTarget.value)}
        size="xs"
      />
      <TextInput
        label={`Type "${clusterName}" to confirm`}
        value={confirm}
        onChange={(e) => setConfirm(e.currentTarget.value)}
        size="xs"
      />
      <Button
        size="xs"
        color="red"
        disabled={!armed}
        loading={rotate.isPending}
        onClick={() =>
          rotate.mutate(
            { username, password, kind },
            {
              onSuccess: () => {
                notifications.show({
                  message: `${CREDENTIAL_KINDS[kind].label} credentials saved — the next scrape will use them`,
                });
                setUsername('');
                setPassword('');
                setConfirm('');
              },
              onError: (err) => notifications.show({ color: 'red', message: err.message }),
            },
          )
        }
      >
        Save {CREDENTIAL_KINDS[kind].label.toLowerCase()} credentials
      </Button>
      <Text size="xs" c="dimmed">
        The new secret is AES-GCM sealed and the change is audited. It replaces this cluster’s
        stored {CREDENTIAL_KINDS[kind].label.toLowerCase()} account on every node; the other account
        is left alone.
      </Text>
    </Stack>
  );
}

/** Settings section: rotate the broker accounts Studio uses for this cluster. */
export function CredentialsSection({ clusterId }: { clusterId: string }) {
  const cluster = useCluster(clusterId);
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        The accounts Studio uses to reach every node of{' '}
        <strong>{cluster.data?.name ?? 'this cluster'}</strong>. Management and Core are stored
        separately, so a cluster whose management account is its <code>&lt;cluster-user&gt;</code>
        can still open a Core connection.
      </Text>
      {cluster.data ? <CredentialRotation clusterId={clusterId} clusterName={cluster.data.name} /> : null}
    </>
  );
}

/** Settings section: what this connection can and cannot do. */
export function CapabilitiesSection({ clusterId }: { clusterId: string }) {
  const cluster = useCluster(clusterId);
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        What this connection can and cannot do over Jolokia. Rows that are not plainly available
        expand with the reason and the exact <code>broker.xml</code> change to close the gap.
      </Text>
      {cluster.data ? <CapabilityLedger capabilities={cluster.data.capabilities} clusterId={clusterId} /> : null}
    </>
  );
}

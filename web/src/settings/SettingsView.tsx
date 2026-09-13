import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Divider,
  Group,
  Loader,
  PasswordInput,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useParams } from '@tanstack/react-router';

import {
  useCluster,
  useResetSetting,
  useRotateCredentials,
  useSettings,
  useUpdateSetting,
} from '../api/client.ts';
import { RegisterClusterButton } from '../clusters/RegisterCluster.tsx';
import { CapabilityLedger } from '../clusters/CapabilityLedger.tsx';
import { DisplayPreferences } from './DisplayPreferences.tsx';
import { NotificationChannels } from './NotificationChannels.tsx';
import { IndexSubscriptions } from './IndexSubscriptions.tsx';

/**
 * The settings form is generated from the API, not from a list kept here. Every
 * key carries its own group, label, hint and kind (ADR-0047), so adding a setting
 * on the server adds it to this screen with no frontend change — and, more to the
 * point, a hint can never drift out of date with the behaviour it describes,
 * which is exactly what happened to the previous hardcoded list.
 */
export function OperationalConfig() {
  const settings = useSettings();
  const update = useUpdateSetting();
  const reset = useResetSetting();
  const [draft, setDraft] = useState<Record<string, string>>({});

  const entries = useMemo(
    () => Object.entries(settings.data?.settings ?? {}),
    [settings.data],
  );

  // Group in first-seen order: the server sends the registry order on purpose.
  const groups = useMemo(() => {
    const out: { name: string; keys: string[] }[] = [];
    for (const [key, value] of entries) {
      const existing = out.find((g) => g.name === value.group);
      if (existing) existing.keys.push(key);
      else out.push({ name: value.group, keys: [key] });
    }
    return out;
  }, [entries]);

  useEffect(() => {
    if (settings.data) {
      setDraft(Object.fromEntries(entries.map(([k, v]) => [k, v.value])));
    }
  }, [settings.data, entries]);

  if (settings.isError) {
    return (
      <Alert color="red" variant="light" title={settings.error.title}>
        {settings.error.message}
      </Alert>
    );
  }

  if (settings.isPending) {
    return <Loader size="sm" />;
  }

  return (
    <Stack gap="lg">
      {groups.map((group) => (
        <Stack key={group.name} gap="sm" maw={560}>
          <Text size="sm" fw={600}>
            {group.name}
          </Text>
          {group.keys.map((key) => {
            const current = settings.data?.settings[key];
            if (!current) return null;
            const value = draft[key] ?? '';
            const dirty = value !== current.value;
            return (
              <div key={key}>
                <Group align="flex-end" gap="xs">
                  <TextInput
                    label={current.label}
                    description={current.hint}
                    value={value}
                    inputMode={current.kind === 'INT' ? 'numeric' : 'text'}
                    onChange={(e) => {
                      const v = e.currentTarget.value;
                      setDraft((d) => ({ ...d, [key]: v }));
                    }}
                    w={300}
                    size="xs"
                  />
                  <Button
                    size="xs"
                    disabled={!dirty}
                    loading={update.isPending}
                    onClick={() =>
                      update.mutate(
                        { key, value },
                        {
                          onSuccess: () =>
                            notifications.show({ message: `${current.label} saved` }),
                          onError: (err) =>
                            notifications.show({ color: 'red', message: err.message }),
                        },
                      )
                    }
                  >
                    Save
                  </Button>
                  {current.overridden ? (
                    <Button size="xs" variant="subtle" onClick={() => reset.mutate(key)}>
                      Reset
                    </Button>
                  ) : null}
                </Group>
                {current.overridden ? (
                  <Text size="xs" c="dimmed">
                    overridden — default is {current.defaultValue}
                  </Text>
                ) : null}
              </div>
            );
          })}
        </Stack>
      ))}
    </Stack>
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
    hint: 'The HTTP Basic account Studio uses to reach every node\u2019s management endpoint. Also used for the Core protocol when no Core account is stored.',
  },
  CORE: {
    label: 'Core protocol',
    hint: 'A separate broker account for the Core connection (notifications and faithful message I/O). Set this when the management account is the broker\u2019s <cluster-user>, which Artemis refuses to authenticate over Core with AMQ229099.',
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
                  message: `${CREDENTIAL_KINDS[kind].label} credentials saved \u2014 the next scrape will use them`,
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
        The new secret is AES-GCM sealed and the change is audited. It replaces this cluster\u2019s
        stored {CREDENTIAL_KINDS[kind].label.toLowerCase()} account on every node; the other account
        is left alone.
      </Text>
    </Stack>
  );
}

/** Operational config, cluster registration, and broker-credential rotation (Slice 9). */
export function SettingsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const cluster = useCluster(clusterId);

  return (
    <Stack gap="xl" maw={640}>
      {/* First, and separate: the only section here that is yours alone. Placing it
          above the shared, audited settings also reads as an escalation — personal,
          then cluster-wide. */}
      <div>
        <Title order={3}>Display</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Yours alone. Stored in this browser, applied immediately, and never sent to the server —
          changing it needs no permission and affects nobody else&rsquo;s screen.
        </Text>
        <DisplayPreferences />
      </div>

      <Divider />

      <div>
        <Title order={3}>Operational configuration</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Overrides the packaged defaults. Stored in Postgres, not the container, and
          applied without a restart. Reset clears the override and the packaged default
          takes over again.
        </Text>
        <OperationalConfig />
      </div>

      <Divider />

      <div>
        <Title order={3}>Clusters</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Register another cluster, or manage this one from its header (Check rediscovers, Remove
          needs its typed name).
        </Text>
        <RegisterClusterButton />
      </div>

      <Divider />

      <div>
        <Title order={3}>Broker credentials</Title>
        <Text size="sm" c="dimmed" mb="sm">
          The accounts Studio uses to reach every node of{' '}
          <strong>{cluster.data?.name ?? 'this cluster'}</strong>. Management and Core are stored
          separately, so a cluster whose management account is its <code>&lt;cluster-user&gt;</code>
          can still open a Core connection.
        </Text>
        {cluster.data ? (
          <CredentialRotation clusterId={clusterId} clusterName={cluster.data.name} />
        ) : null}
      </div>

      <Divider />

      <div>
        <Title order={3}>Connection capabilities</Title>
        <Text size="sm" c="dimmed" mb="sm">
          What this connection can and cannot do over Jolokia. Rows that are not plainly available
          expand with the reason and the exact <code>broker.xml</code> change to close the gap.
        </Text>
        {cluster.data ? <CapabilityLedger capabilities={cluster.data.capabilities} clusterId={clusterId} /> : null}
      </div>

      <Divider />

      <div>
        <Title order={3}>Message index</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Which queues the SQL Console keeps a searchable copy of, so a question can be answered
          after the message has been consumed. Off by default: an index holds message payload, and
          starting one is a deliberate, audited choice with a retention period attached.
        </Text>
        <IndexSubscriptions />
      </div>

      <Divider />

      <div>
        <Title order={3}>Notification channels</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Slack and webhook destinations alert rules can route to — global, not per cluster, since
          one channel commonly serves several clusters.
        </Text>
        <NotificationChannels />
      </div>
    </Stack>
  );
}

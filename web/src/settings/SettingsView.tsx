import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Divider,
  Group,
  Loader,
  PasswordInput,
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
import { NotificationChannels } from './NotificationChannels.tsx';

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

function CredentialRotation({ clusterId, clusterName }: { clusterId: string; clusterName: string }) {
  const rotate = useRotateCredentials(clusterId);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const armed = confirm === clusterName && username.length > 0 && password.length > 0;

  return (
    <Stack gap="xs" maw={420}>
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
            { username, password },
            {
              onSuccess: () => {
                notifications.show({ message: 'Credentials rotated — the next scrape will use them' });
                setUsername('');
                setPassword('');
                setConfirm('');
              },
              onError: (err) => notifications.show({ color: 'red', message: err.message }),
            },
          )
        }
      >
        Rotate credentials
      </Button>
      <Text size="xs" c="dimmed">
        The new secret is AES-GCM sealed and the change is audited. It replaces the credentials for
        every node of this cluster.
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
          Rotate the HTTP Basic credentials Studio uses to reach every node of{' '}
          <strong>{cluster.data?.name ?? 'this cluster'}</strong>.
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
        {cluster.data ? <CapabilityLedger capabilities={cluster.data.capabilities} /> : null}
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

import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Divider,
  Group,
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

const FIELDS: { key: string; label: string; hint: string }[] = [
  { key: 'scrape.tier-a-interval', label: 'Tier A interval', hint: 'HA state + topology (e.g. 5s). Takes effect on restart.' },
  { key: 'scrape.tier-b-interval', label: 'Tier B interval', hint: 'Fast queue refresh (e.g. 15s). Takes effect on restart.' },
  { key: 'scrape.tier-c-interval', label: 'Tier C interval', hint: 'Full queue sweep (e.g. 5m). Takes effect on restart.' },
  { key: 'rate-limit.calls-per-second', label: 'Per-node call ceiling', hint: 'Management calls/sec per broker. Applies on the next tick.' },
  { key: 'metric.retention-days', label: 'Metric retention (days)', hint: 'Raw metric_sample rows older than this are trimmed nightly.' },
];

function OperationalConfig() {
  const settings = useSettings();
  const update = useUpdateSetting();
  const reset = useResetSetting();
  const [draft, setDraft] = useState<Record<string, string>>({});

  useEffect(() => {
    if (settings.data) {
      setDraft(
        Object.fromEntries(
          Object.entries(settings.data.settings).map(([k, v]) => [k, v.value]),
        ),
      );
    }
  }, [settings.data]);

  if (settings.isError) {
    return (
      <Alert color="red" variant="light" title={settings.error.title}>
        {settings.error.message}
      </Alert>
    );
  }

  return (
    <Stack gap="sm" maw={520}>
      {FIELDS.map((f) => {
        const current = settings.data?.settings[f.key];
        const value = draft[f.key] ?? '';
        const dirty = current != null && value !== current.value;
        return (
          <div key={f.key}>
            <Group align="flex-end" gap="xs">
              <TextInput
                label={f.label}
                description={f.hint}
                value={value}
                onChange={(e) => {
                  const v = e.currentTarget.value;
                  setDraft((d) => ({ ...d, [f.key]: v }));
                }}
                w={280}
                size="xs"
              />
              <Button
                size="xs"
                disabled={!dirty}
                loading={update.isPending}
                onClick={() =>
                  update.mutate(
                    { key: f.key, value },
                    {
                      onSuccess: () => notifications.show({ message: `${f.label} saved` }),
                      onError: (err) =>
                        notifications.show({ color: 'red', message: err.message }),
                    },
                  )
                }
              >
                Save
              </Button>
              {current?.overridden ? (
                <Button
                  size="xs"
                  variant="subtle"
                  onClick={() => reset.mutate(f.key)}
                >
                  Reset
                </Button>
              ) : null}
            </Group>
            {current?.overridden ? (
              <Text size="xs" c="dimmed">
                overridden — default is {current.defaultValue}
              </Text>
            ) : null}
          </div>
        );
      })}
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
          Overrides the deploy-time defaults. Stored in Postgres, not the container.
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

      <Text size="xs" c="dimmed">
        Users, roles and OIDC are Phase 8.
      </Text>
    </Stack>
  );
}

import { useState } from 'react';
import {
  Alert,
  Button,
  Group,
  Modal,
  PasswordInput,
  Stack,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  useCheckConnection,
  useRegisterCluster,
  type RegisterClusterRequest,
} from '../api/client.ts';

const EXAMPLE = 'http://broker-1:8161/console/jolokia';

interface Fields {
  seeds: string;
  name: string;
  username: string;
  password: string;
  tlsBundle: string;
}

const EMPTY: Fields = {
  seeds: '',
  name: '',
  username: '',
  password: '',
  tlsBundle: '',
};

/** The registration form. Rendered inline on the empty state, in a modal after. */
export function RegisterClusterForm({ onRegistered }: { onRegistered?: () => void }) {
  const [f, setF] = useState<Fields>(EMPTY);
  const [touched, setTouched] = useState<Record<keyof Fields, boolean>>({
    seeds: false,
    name: false,
    username: false,
    password: false,
    tlsBundle: false,
  });

  const check = useCheckConnection();
  const register = useRegisterCluster();

  const seedList = f.seeds
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);

  const seedsError =
    touched.seeds && seedList.length === 0
      ? 'Add at least one management URL.'
      : touched.seeds && seedList.some((s) => !isUrl(s))
        ? 'Each line must be a full URL.'
        : null;
  const credError =
    (touched.username || touched.password) &&
    Boolean(f.username) !== Boolean(f.password)
      ? 'Provide both a username and a password, or neither.'
      : null;

  const valid = seedList.length > 0 && !seedsError && !credError;

  function payload(): RegisterClusterRequest {
    return {
      seedUrls: seedList,
      name: f.name || undefined,
      credentials: f.username
        ? { username: f.username, password: f.password }
        : undefined,
      tlsBundle: f.tlsBundle || undefined,
    };
  }

  function field(key: keyof Fields) {
    return {
      value: f[key],
      onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
        setF((s) => ({ ...s, [key]: e.currentTarget.value })),
      onBlur: () => setTouched((s) => ({ ...s, [key]: true })),
    };
  }

  return (
    <Stack gap="sm">
      <Textarea
        label="Broker management URLs"
        description={`One per line. For example: ${EXAMPLE}`}
        placeholder={EXAMPLE}
        autosize
        minRows={2}
        error={seedsError}
        {...field('seeds')}
      />
      <TextInput
        label="Name"
        description="Optional. Defaults to the first broker's host."
        placeholder="prod-emea"
        {...field('name')}
      />
      <Group grow align="flex-start">
        <TextInput
          label="Username"
          autoComplete="off"
          error={credError}
          {...field('username')}
        />
        <PasswordInput label="Password" autoComplete="off" {...field('password')} />
      </Group>
      <TextInput
        label="TLS bundle"
        description="Optional. Name of a Spring SSL bundle for an HTTPS broker."
        {...field('tlsBundle')}
      />

      <div aria-live="polite">
        {check.isSuccess ? (
          <Alert color="pine" variant="light" title="Reached this broker">
            {`Found ${check.data.discoveredNodes} node${
              check.data.discoveredNodes === 1 ? '' : 's'
            } across ${check.data.reachableSeeds} address${
              check.data.reachableSeeds === 1 ? '' : 'es'
            }. Nothing saved yet.`}
          </Alert>
        ) : null}
        {check.isError ? (
          <Alert color="red" variant="light" title={check.error.title}>
            {check.error.message}
          </Alert>
        ) : null}
        {register.isError ? (
          <Alert color="red" variant="light" title={register.error.title}>
            {register.error.message}
          </Alert>
        ) : null}
      </div>

      <Group justify="flex-end" gap="sm">
        <Button
          variant="default"
          loading={check.isPending}
          disabled={!valid}
          onClick={() => check.mutate(payload())}
        >
          Check connection
        </Button>
        <Button
          loading={register.isPending}
          disabled={!valid}
          onClick={() =>
            register.mutate(payload(), {
              onSuccess: (detail) => {
                notifications.show({
                  color: 'pine',
                  title: 'Cluster registered',
                  message: detail.name,
                });
                setF(EMPTY);
                onRegistered?.();
              },
            })
          }
        >
          Register cluster
        </Button>
      </Group>
    </Stack>
  );
}

/** The empty state: one thing to do, rendered inline at zero clicks. */
export function EmptyState() {
  return (
    <Stack gap="xs" maw={560}>
      <Text fw={600}>No clusters yet.</Text>
      <Text size="sm" c="dimmed">
        Point Studio at one broker and it will find the rest of the cluster from
        there.
      </Text>
      <RegisterClusterForm />
    </Stack>
  );
}

/** The post-empty affordance: a button that opens the form in a modal. */
export function RegisterClusterButton() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant="default" size="xs" onClick={() => setOpen(true)}>
        Register cluster
      </Button>
      <Modal
        opened={open}
        onClose={() => setOpen(false)}
        title="Register cluster"
        size="lg"
      >
        <RegisterClusterForm onRegistered={() => setOpen(false)} />
      </Modal>
    </>
  );
}

function isUrl(value: string): boolean {
  try {
    const u = new URL(value);
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
}

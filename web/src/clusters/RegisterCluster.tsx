import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Grid,
  Group,
  Modal,
  PasswordInput,
  Stack,
  Text,
  Textarea,
  TextInput,
  UnstyledButton,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from '@tanstack/react-router';

import {
  useCheckConnection,
  useRegisterCluster,
  type RegisterClusterRequest,
} from '../api/client.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import { normaliseSeeds } from './normaliseSeeds.ts';
import { RegisterCanvas } from './RegisterCanvas.tsx';
import type { ExampleShape } from './examples.ts';

const EXAMPLE = 'http://broker-1:8161/console/jolokia';

interface Fields {
  seeds: string;
  name: string;
  username: string;
  password: string;
  coreUsername: string;
  corePassword: string;
  tlsBundle: string;
}

const EMPTY: Fields = {
  seeds: '',
  name: '',
  username: '',
  password: '',
  coreUsername: '',
  corePassword: '',
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
    coreUsername: false,
    corePassword: false,
    tlsBundle: false,
  });
  const [shape, setShape] = useState<ExampleShape | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [checkedSeeds, setCheckedSeeds] = useState<string | null>(null);

  const check = useCheckConnection();
  const register = useRegisterCluster();
  const navigate = useNavigate();

  const normalised = normaliseSeeds(f.seeds);
  const seedList = normalised.map((s) => s.url).filter((u): u is string => u !== null);
  const rewritten = normalised.filter((s) => s.url !== null && s.url !== s.original);
  const unparseable = normalised.filter((s) => s.url === null);

  const seedsError =
    touched.seeds && normalised.length === 0
      ? 'Add at least one management URL.'
      : touched.seeds && unparseable.length > 0
        ? `Couldn't make sense of: ${unparseable.map((s) => s.original).join(', ')}`
        : null;
  const credError =
    (touched.username || touched.password) &&
    Boolean(f.username) !== Boolean(f.password)
      ? 'Provide both a username and a password, or neither.'
      : null;

  const coreCredError =
    (touched.coreUsername || touched.corePassword) &&
    Boolean(f.coreUsername) !== Boolean(f.corePassword)
      ? 'Provide both a Core username and password, or neither.'
      : null;

  const valid =
    seedList.length > 0 && !seedsError && !credError && !coreCredError;

  const stale = check.isSuccess && checkedSeeds !== null && checkedSeeds !== JSON.stringify(seedList);

  // Open the advanced fields on their own once a check reveals they'd matter —
  // the operator never has to know they exist until the ledger says so.
  useEffect(() => {
    if (!check.data) return;
    const gap =
      check.data.capabilities.messageIo.status !== 'AVAILABLE' ||
      check.data.capabilities.notifications.status !== 'AVAILABLE';
    if (gap) setAdvancedOpen(true);
  }, [check.data]);

  function payload(): RegisterClusterRequest {
    return {
      seedUrls: seedList,
      name: f.name || undefined,
      credentials: f.username
        ? { username: f.username, password: f.password }
        : undefined,
      coreCredentials: f.coreUsername
        ? { username: f.coreUsername, password: f.corePassword }
        : undefined,
      tlsBundle: f.tlsBundle || undefined,
    };
  }

  function field(key: keyof Fields) {
    return {
      value: f[key],
      onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
        const v = e.currentTarget.value;
        setF((s) => ({ ...s, [key]: v }));
      },
      onBlur: () => setTouched((s) => ({ ...s, [key]: true })),
    };
  }

  return (
    <Grid gap="lg">
      <Grid.Col span={{ base: 12, md: 6 }}>
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
          {rewritten.length > 0 ? (
            <Text size="xs" c="dimmed">
              Normalised to:{' '}
              {rewritten.map((s, i) => (
                <Text key={s.original} span size="xs" ff="monospace">
                  {i > 0 ? ', ' : ''}
                  {s.url}
                </Text>
              ))}
            </Text>
          ) : null}
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

          <UnstyledButton
            onClick={() => setAdvancedOpen((o) => !o)}
            aria-expanded={advancedOpen}
            c="dimmed"
            fz="xs"
          >
            {advancedOpen ? '⌄' : '›'} Advanced — Core protocol and TLS
          </UnstyledButton>
          <Collapse expanded={advancedOpen}>
            <Stack gap="sm">
              <Group grow align="flex-start">
                <TextInput
                  label="Core username"
                  description="Optional. Defaults to the Jolokia credentials above."
                  autoComplete="off"
                  error={coreCredError}
                  {...field('coreUsername')}
                />
                <PasswordInput
                  label="Core password"
                  autoComplete="off"
                  {...field('corePassword')}
                />
              </Group>
              <TextInput
                label="TLS bundle"
                description="Optional. Name of a Spring SSL bundle for an HTTPS broker."
                {...field('tlsBundle')}
              />
            </Stack>
          </Collapse>

          <div aria-live="polite">
            {check.isSuccess ? (
              <Text size="sm" c="dimmed">
                {`Found ${check.data.discoveredNodes} node${
                  check.data.discoveredNodes === 1 ? '' : 's'
                } across ${check.data.reachableSeeds} address${
                  check.data.reachableSeeds === 1 ? '' : 'es'
                }. Nothing saved yet.`}
              </Text>
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

          {check.isSuccess ? <CapabilityLedger capabilities={check.data.capabilities} /> : null}

          <Group justify="flex-end" gap="sm">
            <Button
              variant="default"
              loading={check.isPending}
              disabled={!valid}
              onClick={() => {
                setCheckedSeeds(JSON.stringify(seedList));
                check.mutate(payload());
              }}
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
                    navigate({ to: `/clusters/${detail.id}/topology` });
                  },
                })
              }
            >
              Register cluster
            </Button>
          </Group>
        </Stack>
      </Grid.Col>
      <Grid.Col span={{ base: 12, md: 6 }}>
        <RegisterCanvas
          preview={check.data}
          stale={stale}
          shape={shape}
          onSelectShape={setShape}
        />
      </Grid.Col>
    </Grid>
  );
}

/** The empty state: one thing to do, rendered inline at zero clicks. */
export function EmptyState() {
  return (
    <Stack gap="xs">
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
        size="xl"
      >
        <RegisterClusterForm onRegistered={() => setOpen(false)} />
      </Modal>
    </>
  );
}

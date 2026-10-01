import { useEffect, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Button,
  Collapse,
  Grid,
  Group,
  Modal,
  PasswordInput,
  ScrollArea,
  Stack,
  Text,
  Textarea,
  TextInput,
  Tooltip,
  UnstyledButton,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPlus } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import {
  useCheckConnection,
  useClusters,
  useRegisterCluster,
  type RegisterClusterRequest,
  type TopologyView,
} from './api.ts';
import { useSlot } from '../../kernel/slots.ts';
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

/** What is wrong with the management URLs typed so far, once the field has been touched. */
function seedsProblem(count: number, unparseable: { original: string }[]): string | null {
  if (count === 0) return 'Add at least one management URL.';
  if (unparseable.length > 0) return `Couldn't make sense of: ${unparseable.map((s) => s.original).join(', ')}`;
  return null;
}

/** A username and password go together; one without the other is the mistake to name. */
function unpairedProblem(touched: boolean, username: string, password: string, message: string): string | null {
  return touched && Boolean(username) !== Boolean(password) ? message : null;
}

/** Why registering is not offered yet, once the inputs are valid. */
function blockedReason(
  check: ReturnType<typeof useCheckConnection>,
  checkPassed: boolean,
  stale: boolean,
): string | null {
  if (check.isPending) return 'Checking the connection…';
  if (checkPassed) return null;
  if (stale) return 'Check the connection again — the details changed since the last check.';
  if (check.isError) return 'The check failed. Fix what it reports above, then check again.';
  return 'Check the connection first.';
}

/** What the connection check found, or why it failed, and why registering failed if it did. */
function CheckOutcome({
  check,
  register,
}: Readonly<{ check: ReturnType<typeof useCheckConnection>; register: ReturnType<typeof useRegisterCluster> }>) {
  return (
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
      {check.isSuccess ? <UntestedVersions topology={check.data.topology} /> : null}
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
  );
}

/** The registration form. Rendered inline on the empty state, in a modal after. */
export function RegisterClusterForm({ onRegistered }: Readonly<{ onRegistered?: () => void }>) {
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
  const [checkedInputs, setCheckedInputs] = useState<string | null>(null);

  const check = useCheckConnection();
  const register = useRegisterCluster();
  const navigate = useNavigate();

  const normalised = normaliseSeeds(f.seeds);
  const seedList = normalised.map((s) => s.url).filter((u): u is string => u !== null);
  const rewritten = normalised.filter((s) => s.url !== null && s.url !== s.original);
  const unparseable = normalised.filter((s) => s.url === null);

  const seedsError = touched.seeds ? seedsProblem(normalised.length, unparseable) : null;
  const credError = unpairedProblem(
    touched.username || touched.password,
    f.username,
    f.password,
    'Provide both a username and a password, or neither.',
  );
  const coreCredError = unpairedProblem(
    touched.coreUsername || touched.corePassword,
    f.coreUsername,
    f.corePassword,
    'Provide both a Core username and password, or neither.',
  );

  const valid = seedList.length > 0 && !seedsError && !credError && !coreCredError;

  // Everything the check's verdict depends on, credentials included — a check that
  // stayed valid across a password edit would vouch for credentials it never saw,
  // which is the exact failure this gate exists to prevent.
  const inputSignature = JSON.stringify([
    seedList,
    f.username,
    f.password,
    f.coreUsername,
    f.corePassword,
    f.tlsBundle,
  ]);
  const checkedThis = checkedInputs === inputSignature;
  const stale = check.isSuccess && checkedInputs !== null && !checkedThis;

  // Registering is gated on a passing check of these exact inputs. The check now
  // opens a real Core subscription, so it is the only thing that can catch a Core
  // account the broker refuses — the failure that otherwise surfaces after
  // registration, where it reads as a broken cluster rather than a typo.
  const checkPassed = check.isSuccess && checkedThis;
  const afterProbe = useSlot('cluster.registration.afterProbe');
  const registerBlockedReason = valid ? blockedReason(check, checkPassed, stale) : null;

  // Open the advanced fields on their own once a check reveals they'd matter —
  // the operator never has to know they exist until the ledger says so.
  useEffect(() => {
    if (!check.data) return;
    // A refusal, not an unproven capability: at check time no write has been
    // attempted, so messageIo is legitimately UNKNOWN and opening the advanced
    // fields for it would fire on every single registration.
    const gap =
      check.data.capabilities.messageIo.status === 'UNAVAILABLE' ||
      check.data.capabilities.notifications.status === 'UNAVAILABLE';
    if (gap) setAdvancedOpen(true);
  }, [check.data]);

  function payload(): RegisterClusterRequest {
    return {
      seedUrls: seedList,
      name: f.name || undefined,
      credentials: f.username ? { username: f.username, password: f.password } : undefined,
      coreCredentials: f.coreUsername ? { username: f.coreUsername, password: f.corePassword } : undefined,
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
            <TextInput label="Username" autoComplete="off" error={credError} {...field('username')} />
            <PasswordInput label="Password" autoComplete="off" {...field('password')} />
          </Group>

          <UnstyledButton onClick={() => setAdvancedOpen((o) => !o)} aria-expanded={advancedOpen} c="dimmed" fz="xs">
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
                <PasswordInput label="Core password" autoComplete="off" {...field('corePassword')} />
              </Group>
              <TextInput
                label="TLS bundle"
                description="Optional. Name of a Spring SSL bundle for an HTTPS broker."
                {...field('tlsBundle')}
              />
            </Stack>
          </Collapse>

          <CheckOutcome check={check} register={register} />

          {check.isSuccess ? <CapabilityLedger capabilities={check.data.capabilities} /> : null}

          {/* What the enabled features make of the check, such as the configuration it
              recommends; each reads its own part of the check's contributions. */}
          {check.isSuccess
            ? afterProbe.map(({ id, Component }) => <Component key={id} contributions={check.data.contributions} />)
            : null}

          <Group justify="flex-end" gap="sm" align="center">
            {registerBlockedReason ? (
              <Text size="xs" c="dimmed">
                {registerBlockedReason}
              </Text>
            ) : null}
            <Button
              variant="default"
              loading={check.isPending}
              disabled={!valid}
              onClick={() => {
                setCheckedInputs(inputSignature);
                check.mutate(payload());
              }}
            >
              Check connection
            </Button>
            <Button
              loading={register.isPending}
              disabled={!valid || !checkPassed}
              onClick={() =>
                register.mutate(payload(), {
                  onSuccess: (detail) => {
                    notifications.show({
                      title: 'Cluster registered',
                      message: detail.name,
                    });
                    setF(EMPTY);
                    onRegistered?.();
                    void navigate({ to: `/clusters/${detail.id}/topology` });
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
        <RegisterCanvas preview={check.data} stale={stale} shape={shape} onSelectShape={setShape} />
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
        Point Studio at one broker and it will find the rest of the cluster from there.
      </Text>
      <RegisterClusterForm />
    </Stack>
  );
}

/** The post-empty affordance: a button that opens the form in a modal. */
export function RegisterClusterButton({ collapsed }: Readonly<{ collapsed?: boolean }>) {
  const [open, setOpen] = useState(false);
  const clusters = useClusters();
  const existing = clusters.data?.length ?? 0;

  return (
    <>
      {collapsed ? (
        <Tooltip label="Register cluster" position="right" withArrow openDelay={350}>
          <ActionIcon variant="default" size="md" aria-label="Register cluster" onClick={() => setOpen(true)}>
            <IconPlus size={18} stroke={1.5} />
          </ActionIcon>
        </Tooltip>
      ) : (
        <Button variant="default" size="xs" onClick={() => setOpen(true)}>
          Register cluster
        </Button>
      )}
      <Modal
        opened={open}
        onClose={() => setOpen(false)}
        title="Register cluster"
        size="xl"
        centered
        scrollAreaComponent={ScrollArea.Autosize}
      >
        <Stack gap="md">
          {existing > 0 ? (
            <Text size="sm" c="dimmed">
              {existing === 1
                ? 'One cluster is already registered. This adds another.'
                : `${existing} clusters are already registered. This adds another.`}
            </Text>
          ) : null}
          <RegisterClusterForm onRegistered={() => setOpen(false)} />
        </Stack>
      </Modal>
    </>
  );
}

/**
 * Nodes on a release newer than Studio has been tested against (ADR-0142). Registration
 * goes ahead; the operator is told before they rely on it rather than after.
 */
function UntestedVersions({ topology }: Readonly<{ topology: TopologyView }>) {
  const untested = topology.nodes.flatMap((n) => n.endpoints).filter((e) => e.versionSupport === 'NEWER_THAN_TESTED');
  if (untested.length === 0) return null;
  return (
    <Alert color="yellow" variant="light" title="Newer Artemis than Studio has tested" mt="xs">
      {`${untested.map((e) => `${e.name} runs Artemis ${e.version}`).join('; ')}. Registration will go ahead, but this release is outside the range Studio's tests cover, so a management call may behave differently. The supported versions page lists the tested range.`}
    </Alert>
  );
}

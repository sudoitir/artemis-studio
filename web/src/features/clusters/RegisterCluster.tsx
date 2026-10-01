import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { Button, Collapse, Group, PasswordInput, Radio, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconChevronDown, IconChevronRight } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import {
  useCheckConnection,
  useClusters,
  useRegisterCluster,
  type RegisterClusterRequest,
  type TopologyView,
} from './api.ts';
import { useSlot } from '../../kernel/slots.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import classes from './Clusters.module.css';
import { normaliseSeeds } from './normaliseSeeds.ts';
import { RegisterCanvas } from './RegisterCanvas.tsx';
import { SETUPS, type Setup } from './setups.ts';

const EXAMPLE = 'http://broker-1:8161/console/jolokia';

const REGISTER: ActionVerb = { verb: 'Register', past: 'Registered', progressive: 'Registering' };

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
function unpairedProblem(username: string, password: string, message: string): string | null {
  return Boolean(username) !== Boolean(password) ? message : null;
}

/** Why registering is not offered yet. */
function blockedReason(
  valid: boolean,
  check: ReturnType<typeof useCheckConnection>,
  checkPassed: boolean,
  stale: boolean,
): string | null {
  if (!valid) return 'Fill in the fields marked above, then check the connection.';
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
    <div aria-live="polite" className={classes.form}>
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
      {check.isError ? <ErrorState variant="inline" error={check.error} /> : null}
      {register.isError ? <ErrorState variant="inline" error={register.error} /> : null}
    </div>
  );
}

/** The registration form. Rendered inline on the empty state, in a modal after. */
export function RegisterClusterForm({ onRegistered }: Readonly<{ onRegistered?: () => void }>) {
  const [setup, setSetup] = useState<Setup | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [checkedInputs, setCheckedInputs] = useState<string | null>(null);

  const check = useCheckConnection();
  const register = useRegisterCluster();
  const navigate = useNavigate();

  const form = useForm<Fields>({
    initialValues: EMPTY,
    validateInputOnBlur: true,
    validate: { seeds: () => seedsIssue, username: () => credIssue, coreUsername: () => coreCredIssue },
  });
  const f = form.values;

  const normalised = normaliseSeeds(f.seeds);
  const seedList = normalised.map((s) => s.url).filter((u): u is string => u !== null);
  const rewritten = normalised.filter((s) => s.url !== null && s.url !== s.original);
  const unparseable = normalised.filter((s) => s.url === null);

  // What is wrong, whether or not the field was touched: that decides if the form can be sent. A message
  // shows beside its field once the field was left, or the form was pressed while invalid.
  const seedsIssue = seedsProblem(normalised.length, unparseable);
  const credIssue = unpairedProblem(f.username, f.password, 'Provide both a username and a password, or neither.');
  const coreCredIssue = unpairedProblem(
    f.coreUsername,
    f.corePassword,
    'Provide both a Core username and password, or neither.',
  );

  const valid = !seedsIssue && !credIssue && !coreCredIssue;

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
  const registerBlockedReason = blockedReason(valid, check, checkPassed, stale);

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

  // A rejected press shows every field's message and takes the first invalid one into focus. The
  // advanced fields are opened first when one of them is the problem, so there is something to focus.
  const checkConnection = form.onSubmit(
    () => {
      setCheckedInputs(inputSignature);
      check.mutate(payload());
    },
    (errors) => {
      if (errors.coreUsername) flushSync(() => setAdvancedOpen(true));
      focusFirstInvalid(form.getInputNode)(errors);
    },
  );

  function payload(): RegisterClusterRequest {
    return {
      seedUrls: seedList,
      name: f.name || undefined,
      credentials: f.username ? { username: f.username, password: f.password } : undefined,
      coreCredentials: f.coreUsername ? { username: f.coreUsername, password: f.corePassword } : undefined,
      tlsBundle: f.tlsBundle || undefined,
    };
  }

  const guide = SETUPS.find((s) => s.value === setup);

  return (
    <div className={classes.register}>
      <Radio.Group
        className={classes.span}
        label="What are you connecting to?"
        description="Studio discovers the rest from the brokers; this only tells you what to enter."
        value={setup}
        onChange={(v) => setSetup(v as Setup)}
      >
        <div className={classes.setups}>
          {SETUPS.map((s) => (
            <Radio.Card key={s.value} value={s.value} className={classes.setup}>
              <Group wrap="nowrap" align="flex-start" gap="sm">
                <Radio.Indicator />
                <div>
                  <Text size="sm" fw={600}>
                    {s.title}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {s.description}
                  </Text>
                </div>
              </Group>
            </Radio.Card>
          ))}
        </div>
      </Radio.Group>
      <form className={classes.form} noValidate onSubmit={checkConnection}>
        <Textarea
          label="Broker management URLs"
          description={`${guide ? guide.urls : 'One per line.'} For example: ${EXAMPLE}`}
          autosize
          minRows={guide?.rows ?? 2}
          {...form.getInputProps('seeds')}
        />
        {rewritten.length > 0 ? (
          <Text size="xs" c="dimmed">
            Normalised to:{' '}
            {rewritten.map((s, i) => (
              <Text key={s.original} span size="xs" className={classes.mono}>
                {i > 0 ? ', ' : ''}
                {s.url}
              </Text>
            ))}
          </Text>
        ) : null}
        <TextInput
          label="Name"
          description="Optional. Defaults to the first broker's host."
          {...form.getInputProps('name')}
        />
        <FieldRow>
          <TextInput label="Username" autoComplete="off" {...form.getInputProps('username')} />
          <PasswordInput label="Password" autoComplete="off" {...form.getInputProps('password')} />
        </FieldRow>

        <Button
          variant="subtle"
          size="compact-sm"
          className={classes.start}
          onClick={() => setAdvancedOpen((o) => !o)}
          aria-expanded={advancedOpen}
          leftSection={
            advancedOpen ? <IconChevronDown size={16} aria-hidden /> : <IconChevronRight size={16} aria-hidden />
          }
        >
          Advanced: Core protocol and TLS
        </Button>
        <Collapse expanded={advancedOpen}>
          <div className={classes.form}>
            <FieldRow>
              <TextInput
                label="Core username"
                description="Optional. Defaults to the Jolokia credentials above."
                autoComplete="off"
                {...form.getInputProps('coreUsername')}
              />
              <PasswordInput label="Core password" autoComplete="off" {...form.getInputProps('corePassword')} />
            </FieldRow>
            <TextInput
              label="TLS bundle"
              description="Optional. Name of a Spring SSL bundle for an HTTPS broker."
              {...form.getInputProps('tlsBundle')}
            />
          </div>
        </Collapse>

        <CheckOutcome check={check} register={register} />

        {check.isSuccess ? <CapabilityLedger capabilities={check.data.capabilities} /> : null}

        {/* What the enabled features make of the check, such as the configuration it
            recommends; each reads its own part of the check's contributions. */}
        {check.isSuccess
          ? afterProbe.map(({ id, Component }) => <Component key={id} contributions={check.data.contributions} />)
          : null}

        <div className={classes.actions}>
          {registerBlockedReason ? (
            <Text size="xs" c="dimmed" className={classes.reason}>
              {registerBlockedReason}
            </Text>
          ) : null}
          <Button type="submit" variant="default" loading={check.isPending}>
            Check connection
          </Button>
          <Button
            loading={register.isPending}
            disabled={!valid || !checkPassed}
            onClick={() =>
              register.mutate(payload(), {
                onSuccess: (detail) => {
                  notify.succeeded({ action: REGISTER, subject: `cluster ${detail.name}` });
                  form.reset();
                  onRegistered?.();
                  void navigate({ to: `/clusters/${detail.id}/topology` });
                },
              })
            }
          >
            Register cluster
          </Button>
        </div>
      </form>
      <RegisterCanvas preview={check.data} stale={stale} />
    </div>
  );
}

/** What the dialog holds: the form, and a note that this adds to the clusters already registered. */
export function RegisterClusterPanel({ onRegistered }: Readonly<{ onRegistered: () => void }>) {
  const clusters = useClusters();
  const existing = clusters.data?.length ?? 0;

  return (
    <Stack gap="md">
      {existing > 0 ? (
        <Text size="sm" c="dimmed">
          {existing === 1
            ? 'One cluster is already registered. This adds another.'
            : `${existing} clusters are already registered. This adds another.`}
        </Text>
      ) : null}
      <RegisterClusterForm onRegistered={onRegistered} />
    </Stack>
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
    <Notice title="Newer Artemis than Studio has tested" tone="warning">
      {`${untested.map((e) => `${e.name} runs Artemis ${e.version}`).join('; ')}. Registration will go ahead, but this release is outside the range Studio's tests cover, so a management call may behave differently. The supported versions page lists the tested range.`}
    </Notice>
  );
}

import { useEffect, useRef, useState } from 'react';
import { Button, Collapse, PasswordInput, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import {
  useCheckConnection,
  useClusters,
  useRegisterCluster,
  type RegisterClusterRequest,
  type TopologyView,
} from './api.ts';
import type { ApiError } from '../../kernel/api/request.ts';
import { useSlot } from '../../kernel/slots.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import classes from './Clusters.module.css';
import { Notice } from './Notice.tsx';
import { normaliseSeeds } from './normaliseSeeds.ts';
import { RegisterCanvas } from './RegisterCanvas.tsx';
import type { ExampleShape } from './examples.ts';

/** The field a refusal is about: Mantine marks it `aria-invalid`, or, for a password field, on its wrapper. */
const INVALID = '[aria-invalid="true"], [data-error] :is(input, textarea)';

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

/**
 * A refused check or registration. A broker failure reads as its kind, which names the cause and the
 * next step but not what the broker reported (which address, which version); that follows as its own line.
 */
function Failure({ error }: Readonly<{ error: ApiError }>) {
  return (
    <>
      <ErrorState variant="inline" error={error} />
      {error.brokerErrorKind ? (
        <Text size="sm" role="status">
          {error.message}
        </Text>
      ) : null}
    </>
  );
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
      {check.isError ? <Failure error={check.error} /> : null}
      {register.isError ? <Failure error={register.error} /> : null}
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
  const [rejected, setRejected] = useState(0);
  const formRef = useRef<HTMLDivElement>(null);

  const check = useCheckConnection();
  const register = useRegisterCluster();
  const navigate = useNavigate();

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
  const seedsError = touched.seeds ? seedsIssue : null;
  const credError = touched.username || touched.password ? credIssue : null;
  const coreCredError = touched.coreUsername || touched.corePassword ? coreCredIssue : null;

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

  // A rejected press shows every field's message and takes the first invalid one into focus.
  useEffect(() => {
    if (rejected > 0) formRef.current?.querySelector<HTMLElement>(INVALID)?.focus();
  }, [rejected]);

  function reject() {
    setTouched({
      seeds: true,
      name: true,
      username: true,
      password: true,
      coreUsername: true,
      corePassword: true,
      tlsBundle: true,
    });
    if (coreCredIssue) setAdvancedOpen(true);
    setRejected((n) => n + 1);
  }

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
    <div className={classes.register}>
      <div className={classes.form} ref={formRef}>
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
          placeholder="prod-emea"
          {...field('name')}
        />
        <div className={classes.pair}>
          <TextInput label="Username" autoComplete="off" error={credError} {...field('username')} />
          <PasswordInput label="Password" autoComplete="off" {...field('password')} />
        </div>

        <Button
          variant="subtle"
          size="compact-sm"
          className={classes.start}
          onClick={() => setAdvancedOpen((o) => !o)}
          aria-expanded={advancedOpen}
        >
          {advancedOpen ? '⌄' : '›'} Advanced — Core protocol and TLS
        </Button>
        <Collapse expanded={advancedOpen}>
          <div className={classes.form}>
            <div className={classes.pair}>
              <TextInput
                label="Core username"
                description="Optional. Defaults to the Jolokia credentials above."
                autoComplete="off"
                error={coreCredError}
                {...field('coreUsername')}
              />
              <PasswordInput label="Core password" autoComplete="off" {...field('corePassword')} />
            </div>
            <TextInput
              label="TLS bundle"
              description="Optional. Name of a Spring SSL bundle for an HTTPS broker."
              {...field('tlsBundle')}
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
          <Button
            variant="default"
            loading={check.isPending}
            onClick={() => {
              if (!valid) {
                reject();
                return;
              }
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
                  notify.succeeded({ action: REGISTER, subject: `cluster ${detail.name}` });
                  setF(EMPTY);
                  onRegistered?.();
                  void navigate({ to: `/clusters/${detail.id}/topology` });
                },
              })
            }
          >
            Register cluster
          </Button>
        </div>
      </div>
      <RegisterCanvas preview={check.data} stale={stale} shape={shape} onSelectShape={setShape} />
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

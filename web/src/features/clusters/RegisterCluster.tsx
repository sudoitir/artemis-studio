import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { Button, Collapse, PasswordInput, Text, Textarea, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconChevronDown, IconChevronRight } from '@tabler/icons-react';
import { Link, useNavigate } from '@tanstack/react-router';

import {
  alreadyRegistered,
  useCheckConnection,
  useRegisterCluster,
  type ProblemDetail,
  type RegisterClusterRequest,
  type TopologyView,
} from './api.ts';
import { useSlot } from '../../kernel/slots.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import classes from './Clusters.module.css';
import { normaliseSeeds } from './normaliseSeeds.ts';

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
  registeredAlready: boolean,
): string | null {
  if (!valid) return 'Fill in the fields marked above, then check the connection.';
  if (check.isPending) return 'Checking the connection…';
  if (registeredAlready) return 'These brokers are registered already, so there is nothing to register.';
  if (checkPassed) return null;
  if (stale) return 'Check the connection again — the details changed since the last check.';
  if (check.isError) return 'The check failed. Fix what it reports above, then check again.';
  return 'Check the connection first.';
}

/** What the connection check found, or why it failed, and why registering failed if it did. */
function CheckOutcome({
  check,
  register,
  onLeave,
}: Readonly<{
  check: ReturnType<typeof useCheckConnection>;
  register: ReturnType<typeof useRegisterCluster>;
  onLeave?: () => void;
}>) {
  return (
    <div aria-live="polite" className={classes.form}>
      {check.isSuccess ? (
        <Text size="sm" c="dimmed">
          {`Connected. Found ${check.data.discoveredNodes} node${check.data.discoveredNodes === 1 ? '' : 's'}.`}
        </Text>
      ) : null}
      {check.isSuccess ? <UntestedVersions topology={check.data.topology} /> : null}
      {check.isError ? <Failure error={check.error} onLeave={onLeave} /> : null}
      {register.isError ? <Failure error={register.error} onLeave={onLeave} /> : null}
    </div>
  );
}

/** A failed check or registration: brokers that are registered already get their own notice. */
function Failure({ error, onLeave }: Readonly<{ error: unknown; onLeave?: () => void }>) {
  const registered = alreadyRegistered(error);
  return registered ? (
    <AlreadyRegistered problem={registered} onLeave={onLeave} />
  ) : (
    <ErrorState variant="inline" error={error} />
  );
}

/**
 * The brokers already belong to a registered cluster (ADR-0167). Registering them again would show the
 * same brokers twice, so the form says which cluster has them and links to it; when the operator may not
 * see that cluster, the server leaves it unnamed and there is no link.
 */
function AlreadyRegistered({ problem, onLeave }: Readonly<{ problem: ProblemDetail; onLeave?: () => void }>) {
  const { existingClusterId: id, existingClusterName: name } = problem;
  return (
    <Notice
      title={problem.title ?? 'These brokers are already registered'}
      tone="danger"
      action={
        id ? (
          <Link to={`/clusters/${id}`} className={linkClasses.link} onClick={onLeave}>
            {`Open ${name ?? 'that cluster'}`}
          </Link>
        ) : undefined
      }
    >
      {problem.detail}
    </Notice>
  );
}

/**
 * The registration form. Rendered inline on the empty state, in a modal after. `onDone` is called when
 * the form has nothing left to do: a cluster was registered, or the operator went to the cluster that
 * already holds the brokers.
 */
export function RegisterClusterForm({ onDone }: Readonly<{ onDone?: () => void }>) {
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
  // Brokers a registered cluster holds stay refused whichever call found it: the check, or a registration
  // that lost a race with another one. Only a new check of changed details can clear it.
  const registeredAlready = checkedThis && Boolean(alreadyRegistered(check.error) ?? alreadyRegistered(register.error));
  const registerBlockedReason = blockedReason(valid, check, checkPassed, stale, registeredAlready);

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
      register.reset();
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

  return (
    <div className={classes.register}>
      <form className={classes.form} noValidate onSubmit={checkConnection}>
        <Textarea
          label="Broker management URLs"
          description={`One per line. Studio finds the rest of the cluster from these. For example: ${EXAMPLE}`}
          autosize
          minRows={2}
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

        <CheckOutcome check={check} register={register} onLeave={onDone} />

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
            disabled={!valid || !checkPassed || registeredAlready}
            onClick={() =>
              register.mutate(payload(), {
                onSuccess: (detail) => {
                  notify.succeeded({ action: REGISTER, subject: `cluster ${detail.name}` });
                  form.reset();
                  onDone?.();
                  void navigate({ to: `/clusters/${detail.id}/topology` });
                },
              })
            }
          >
            Register cluster
          </Button>
        </div>
      </form>
    </div>
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

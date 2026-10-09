import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { ActionIcon, Button, Collapse, PasswordInput, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { pairProblems, useRevalidatePairs } from '../../ui/formPairs.ts';
import { IconChevronRight, IconX } from '@tabler/icons-react';
import { Link, useNavigate } from '@tanstack/react-router';

import {
  alreadyRegistered,
  useCheckConnection,
  useEnvironments,
  useRegisterCluster,
  type AdoptionPreviewView,
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
import { adoptionCountsWords, defaultPattern } from './connectionWords.ts';
import { NodeProbeTable } from './NodeProbeTable.tsx';
import { normaliseSeeds, seedRow, type SeedRow } from './normaliseSeeds.ts';
import { isValidPattern } from './pattern.ts';

const EXAMPLE = 'http://broker-1:8161/console/jolokia';

const REGISTER: ActionVerb = { verb: 'Register', past: 'Registered', progressive: 'Registering' };

interface Fields {
  seed: string;
  moreSeeds: SeedRow[];
  name: string;
  username: string;
  password: string;
  coreUsername: string;
  corePassword: string;
  environmentId: string;
  tlsBundle: string;
  /** What the operator typed in the pattern field; empty while the field follows the first seed. */
  pattern: string;
}

const EMPTY: Fields = {
  seed: '',
  moreSeeds: [],
  name: '',
  username: '',
  password: '',
  coreUsername: '',
  corePassword: '',
  environmentId: '',
  tlsBundle: '',
  pattern: '',
};

/** What is wrong with one management URL as typed, or null. */
function seedProblem(value: string): string | null {
  const [first] = normaliseSeeds(value);
  if (first?.url === null) return `Couldn't make sense of: ${first.original}`;
  if (first && new URL(first.url).username) return 'Put the account in the Management account fields, not in the URL.';
  return null;
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
  const nodes = check.data?.nodes.length ?? 0;
  return (
    <div aria-live="polite" className={classes.form}>
      {check.isSuccess ? (
        <Text size="sm" c="dimmed">
          {`Connected. Found ${nodes} node${nodes === 1 ? '' : 's'}.`}
        </Text>
      ) : null}
      {check.isSuccess ? <NodeProbeTable nodes={check.data.nodes} label="Nodes found by the check" /> : null}
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
 * What registering can also do: save what the brokers run as the cluster's first declared revision
 * (ADR-0176). On when the nodes agree; off, with each disagreement listed, when they do not, because
 * declaring one node's value over another's is a choice for the operator to make.
 */
function AdoptionChoice({
  adoption,
  adopt,
  onChange,
}: Readonly<{ adoption: AdoptionPreviewView; adopt: boolean; onChange: (value: boolean) => void }>) {
  const disagree = adoption.disagreements.length > 0;
  return (
    <Stack gap="xs">
      <Switch
        label="Adopt what the brokers run as the cluster's first configuration"
        description={`Declares ${adoptionCountsWords(adoption.counts)} as revision 1, so the cluster starts in sync and every later change shows as a difference from it. Nothing is written to a broker.`}
        checked={adopt}
        onChange={(event) => onChange(event.currentTarget.checked)}
      />
      {disagree ? (
        <Notice title={`The nodes disagree on ${adoption.disagreements.length} item(s)`} tone="warning">
          <Stack gap="xs">
            <Text size="sm">
              Adopting keeps the first node&apos;s value for each, so it is off until you choose it.
            </Text>
            {adoption.disagreements.map((d) => (
              <Text key={d} size="sm">
                {d}
              </Text>
            ))}
          </Stack>
        </Notice>
      ) : null}
    </Stack>
  );
}

/** The seeds typed so far, normalised, with the ones that do not parse. */
function seedsOf(f: Fields): { urls: string[]; problems: Record<string, string> } {
  const urls: string[] = [];
  const problems: Record<string, string> = {};
  [f.seed, ...f.moreSeeds.map((row) => row.url)].forEach((value, i) => {
    const path = i === 0 ? 'seed' : `moreSeeds.${i - 1}.url`;
    const issue = seedProblem(value);
    if (issue) problems[path] = issue;
    const [first] = normaliseSeeds(value);
    if (first?.url) urls.push(first.url);
  });
  if (urls.length === 0 && !problems.seed) problems.seed = 'Add at least one management URL.';
  return { urls, problems };
}

const PAIRS: [string, string][] = [
  ['username', 'password'],
  ['coreUsername', 'corePassword'],
];

/** What is wrong with the values as they stand, by field: the form can be sent only when this is empty. */
function problemsOf(values: Fields): Record<string, string> {
  const problems = seedsOf(values).problems;
  if (values.pattern && !isValidPattern(values.pattern)) {
    problems.pattern = 'Use http(s)://{host}[:port][/path], with {host} as the whole host.';
  }
  return {
    ...problems,
    ...pairProblems(
      {
        name: 'username',
        value: values.username,
        missing: 'Enter the username for this password, or clear the password.',
      },
      {
        name: 'password',
        value: values.password,
        missing: 'Enter the password for this username, or clear the username.',
      },
    ),
    ...pairProblems(
      {
        name: 'coreUsername',
        value: values.coreUsername,
        missing: 'Enter the Core username for this password, or clear the password.',
      },
      {
        name: 'corePassword',
        value: values.corePassword,
        missing: 'Enter the Core password for this username, or clear the username.',
      },
    ),
  };
}

/**
 * The registration form. Rendered inline on the empty state, in a modal after. `onDone` is called when
 * the form has nothing left to do: a cluster was registered, or the operator went to the cluster that
 * already holds the brokers.
 */
export function RegisterClusterForm({
  onDone,
  onDirtyChange,
}: Readonly<{
  onDone?: () => void;
  /** Told whether the form holds input, so a dialog around it can keep that input from a stray click. */
  onDirtyChange?: (dirty: boolean) => void;
}>) {
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [checkedInputs, setCheckedInputs] = useState<string | null>(null);
  const [adoptChoice, setAdoptChoice] = useState<boolean | null>(null);

  const check = useCheckConnection();
  const register = useRegisterCluster();
  const environments = useEnvironments();
  const navigate = useNavigate();

  const form = useForm<Fields>({
    initialValues: EMPTY,
    validateInputOnBlur: true,
    validate: problemsOf,
  });
  const f = form.values;
  useRevalidatePairs(form, PAIRS);
  const dirty = form.isDirty();
  useEffect(() => onDirtyChange?.(dirty), [dirty, onDirtyChange]);

  const { urls: seedList } = seedsOf(f);
  const rewritten = [f.seed, ...f.moreSeeds.map((row) => row.url)]
    .map((value) => normaliseSeeds(value)[0])
    .filter((s) => s?.url && s.url !== s.original);
  const pattern = f.pattern || defaultPattern(seedList[0]);

  // What is wrong, whether or not the field was touched: that decides if the form can be sent. A message
  // shows beside its field once the field was left, or the form was pressed while invalid.
  const valid = Object.keys(problemsOf(f)).length === 0;

  // Everything the check's verdict depends on, credentials included — a check that
  // stayed valid across a password edit would vouch for credentials it never saw,
  // which is the exact failure this gate exists to prevent.
  const inputSignature = JSON.stringify([
    seedList,
    pattern,
    f.username,
    f.password,
    f.coreUsername,
    f.corePassword,
    f.tlsBundle,
  ]);
  const checkedThis = checkedInputs === inputSignature;
  const stale = check.isSuccess && checkedInputs !== null && !checkedThis;

  // Registering is gated on a passing check of these exact inputs. The check opens a real Core
  // session per node, so it is the only thing that can catch a Core account the broker refuses —
  // the failure that otherwise surfaces after registration, where it reads as a broken cluster.
  const checkPassed = check.isSuccess && checkedThis;
  const afterProbe = useSlot('cluster.registration.afterProbe');
  // Brokers a registered cluster holds stay refused whichever call found it: the check, or a registration
  // that lost a race with another one. Only a new check of changed details can clear it.
  const registeredAlready = checkedThis && Boolean(alreadyRegistered(check.error) ?? alreadyRegistered(register.error));
  const registerBlockedReason = blockedReason(valid, check, checkPassed, stale, registeredAlready);

  const adoption = checkPassed ? (check.data.adoption ?? null) : null;
  const adopt = adoption ? (adoptChoice ?? adoption.disagreements.length === 0) : false;

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
  const checkConnection = form.onSubmit(
    () => {
      setCheckedInputs(inputSignature);
      setAdoptChoice(null);
      register.reset();
      check.mutate(payload());
    },
    (errors) => {
      if (errors.pattern) flushSync(() => setAdvancedOpen(true));
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
      managementUrlPattern: pattern || undefined,
      environmentId: f.environmentId || undefined,
      adopt,
    };
  }

  const environmentOptions = (environments.data ?? []).map((e) => ({ value: e.id, label: e.name }));

  return (
    <div className={classes.register}>
      <form className={classes.form} noValidate onSubmit={checkConnection}>
        <TextInput
          label="Broker management URL"
          description={`One is enough: Studio finds the rest of the cluster from it. For example: ${EXAMPLE}`}
          {...form.getInputProps('seed')}
        />
        {f.moreSeeds.map((row, i) => (
          <FieldRow key={row.key}>
            <TextInput
              label={`Another management URL (${i + 2})`}
              autoComplete="off"
              {...form.getInputProps(`moreSeeds.${i}.url`)}
            />
            <ActionIcon
              variant="subtle"
              aria-label={`Remove management URL ${i + 2}`}
              onClick={() => form.removeListItem('moreSeeds', i)}
            >
              <IconX size={16} aria-hidden />
            </ActionIcon>
          </FieldRow>
        ))}
        <Button
          variant="subtle"
          size="compact-sm"
          className={classes.start}
          onClick={() => form.insertListItem('moreSeeds', seedRow())}
        >
          Add another seed
        </Button>
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

        <Text size="sm" fw={600}>
          Management account
        </Text>
        <FieldRow>
          <TextInput label="Username" autoComplete="off" {...form.getInputProps('username')} />
          <PasswordInput label="Password" autoComplete="off" {...form.getInputProps('password')} />
        </FieldRow>
        <Text size="sm" fw={600}>
          Core account
        </Text>
        <FieldRow>
          <TextInput
            label="Core username"
            description="Optional. Defaults to the management account."
            autoComplete="off"
            {...form.getInputProps('coreUsername')}
          />
          <PasswordInput label="Core password" autoComplete="off" {...form.getInputProps('corePassword')} />
        </FieldRow>

        <Select
          label="Environment"
          description={
            environmentOptions.length > 0
              ? 'Optional. Groups the cluster with others.'
              : 'Optional. There are no environments yet; create one under Administration.'
          }
          data={environmentOptions}
          disabled={environmentOptions.length === 0}
          clearable
          {...form.getInputProps('environmentId')}
        />

        <Button
          variant="subtle"
          size="compact-sm"
          className={classes.start}
          onClick={() => setAdvancedOpen((o) => !o)}
          aria-expanded={advancedOpen}
          leftSection={
            <IconChevronRight size={16} aria-hidden className={classes.chevron} data-open={advancedOpen || undefined} />
          }
        >
          Advanced: management URL pattern and TLS
        </Button>
        <Collapse expanded={advancedOpen}>
          <div className={classes.form}>
            <TextInput
              label="Management URL pattern"
              description="How Studio finds each other node's management URL: its host replaces {host}. A broker answering there is accepted only when it is that node."
              value={pattern}
              onChange={(event) => form.setFieldValue('pattern', event.currentTarget.value)}
              error={form.errors.pattern}
            />
            <TextInput
              label="TLS bundle"
              description="Optional. Name of a Spring SSL bundle for an HTTPS broker."
              {...form.getInputProps('tlsBundle')}
            />
          </div>
        </Collapse>

        <CheckOutcome check={check} register={register} onLeave={onDone} />

        {check.isSuccess ? <CapabilityLedger capabilities={check.data.capabilities} /> : null}

        {adoption ? <AdoptionChoice adoption={adoption} adopt={adopt} onChange={setAdoptChoice} /> : null}

        {/* What the enabled features make of the check, such as the configuration it
            recommends; each reads its own part of the check's contributions. */}
        {check.isSuccess
          ? afterProbe.map(({ id, Component }) => <Component key={id} contributions={check.data.contributions} />)
          : null}

        <div className={`${classes.actions} ${classes.stickyActions}`}>
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
  const which = untested.map((e) => `${e.name} runs Artemis ${e.version}`).join('; ');
  return (
    <Notice title="Newer Artemis than Studio has tested" tone="warning">
      {`${which}. Registration will go ahead, but this release is outside the range Studio's tests cover, so a management call may behave differently. The supported versions page lists the tested range.`}
    </Notice>
  );
}

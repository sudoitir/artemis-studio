import {
  Alert,
  Anchor,
  Badge,
  Button,
  Checkbox,
  Code,
  Collapse,
  CopyButton,
  Group,
  List,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useDisclosure } from '@mantine/hooks';

import type { PluginPlanView, PluginViolationView } from './api.ts';
import styles from './Plugins.module.css';
import { acknowledgementReasons, count, downtime, TRUST_LABEL } from './words.ts';

function Section({ title, children }: Readonly<{ title: string; children: React.ReactNode }>) {
  return (
    <Stack gap={4}>
      <Title order={4} fz="sm">
        {title}
      </Title>
      {children}
    </Stack>
  );
}

type TrustOffer = Readonly<{ canInstall: boolean; onTrust: () => void }>;

function Fingerprint({ value, copy }: Readonly<{ value: string; copy?: boolean }>) {
  return (
    <Group gap={4} wrap="nowrap" component="span" display="inline-flex" maw="100%">
      <Code className={styles.fingerprint}>{value}</Code>
      {copy ? (
        <CopyButton value={value}>
          {({ copied, copy: doCopy }) => (
            <Button size="compact-xs" variant="subtle" onClick={doCopy} aria-label="Copy fingerprint">
              {copied ? 'Copied' : 'Copy'}
            </Button>
          )}
        </CopyButton>
      ) : null}
    </Group>
  );
}

/** The signer moved between versions: old key to new, or to or from unsigned. */
function SignerChange({ trust }: Readonly<{ trust: PluginPlanView['trust'] }>) {
  return (
    <Text size="sm" className={styles.warning}>
      Signer changed:{' '}
      {trust.previousFingerprint ? (
        <Fingerprint value={trust.previousFingerprint} />
      ) : (
        'the installed version was unsigned'
      )}{' '}
      → {trust.fingerprint ? <Fingerprint value={trust.fingerprint} /> : 'this version is unsigned'}
    </Text>
  );
}

/** Says, for a status that is not simply trusted, why the plugin can or cannot go on. */
function TrustExplanation({ trust }: Readonly<{ trust: PluginPlanView['trust'] }>) {
  if (trust.status === 'TRUSTED') return null;
  if (trust.allowed) {
    return (
      <Text size="sm" className={styles.warning}>
        Unverified: it can be installed only because an installer allowed unverified plugins.
      </Text>
    );
  }
  return trust.status === 'UNSIGNED' ? (
    <Text size="sm">
      This plugin is not signed, and this installation refuses unverified plugins. An installer can allow unverified
      plugins under Trusted keys.
    </Text>
  ) : (
    <Text size="sm">
      No key you trust signed this plugin. Compare the fingerprint with the one the publisher publishes before you trust
      it.
    </Text>
  );
}

/** Trusting a key is an installer's act; anyone else sees the button and why it is off. */
function TrustAction({ offer }: Readonly<{ offer: TrustOffer }>) {
  return (
    <Group gap="xs">
      <Button size="xs" variant="default" disabled={!offer.canInstall} onClick={offer.onTrust}>
        Trust this key…
      </Button>
      {offer.canInstall ? null : (
        <Text size="xs" c="dimmed">
          Only someone who can install plugins can trust a key.
        </Text>
      )}
    </Group>
  );
}

/** Who stands behind the jar: its signer, and whether an installer has said to trust that key. */
function PublisherSection({ plan, trust }: Readonly<{ plan: PluginPlanView; trust?: TrustOffer }>) {
  const t = plan.trust;
  const trusted = t.status === 'TRUSTED';
  return (
    <Section title="Publisher">
      <Group gap="xs">
        <Badge
          size="sm"
          variant={trusted ? 'outline' : 'light'}
          color={trusted ? 'gray' : 'yellow'}
          c={trusted ? undefined : 'var(--as-warning)'}
        >
          {TRUST_LABEL[t.status]}
        </Badge>
        {trusted && t.keyName ? <Text size="sm">Trusted key: {t.keyName}</Text> : null}
      </Group>
      {t.fingerprint ? (
        <>
          <Text size="sm">Signed by {t.subject ?? 'an unnamed certificate'}. Key fingerprint:</Text>
          <Fingerprint value={t.fingerprint} copy />
        </>
      ) : null}
      {t.signerChanged ? <SignerChange trust={t} /> : null}
      <TrustExplanation trust={t} />
      {t.status === 'UNTRUSTED' && trust ? <TrustAction offer={trust} /> : null}
    </Section>
  );
}

/** The reasons an activation needs an explicit yes; the button it gates says so beside it. */
export function Acknowledgement({
  plan,
  checked,
  onChange,
}: Readonly<{
  plan: PluginPlanView;
  checked: boolean;
  onChange: (checked: boolean) => void;
}>) {
  return (
    <Alert variant="light" color="yellow" title="This needs your confirmation">
      <Stack gap="xs">
        <List size="sm" spacing={2}>
          {acknowledgementReasons(plan).map((reason) => (
            <List.Item key={reason}>{reason}</List.Item>
          ))}
        </List>
        <Checkbox
          checked={checked}
          onChange={(e) => onChange(e.currentTarget.checked)}
          label="I have read this and want to continue"
        />
      </Stack>
    </Alert>
  );
}

/** Every capability the plugin asks for, as a sentence. */
function CapabilitiesSection({ plan }: Readonly<{ plan: PluginPlanView }>) {
  const info = plan.info;
  const c = info.contributions;
  const changes = plan.pendingChangesets.length;
  const readTools = c.mcpTools.filter((t) => t.posture === 'read');
  const writeTools = c.mcpTools.filter((t) => t.posture !== 'read');
  return (
    <Section title="What this plugin will be able to do">
      <List size="sm" spacing={2}>
        {c.ui ? <List.Item>Add screens to Studio, which act with the rights of whoever views them.</List.Item> : null}
        <List.Item>Run code inside Studio, with Studio's access to your brokers and database.</List.Item>
        {readTools.length > 0 ? (
          <List.Item>
            Give the assistant {count(readTools.length, 'read-only tool')}: {readTools.map((t) => t.name).join(', ')}.
          </List.Item>
        ) : null}
        {writeTools.length > 0 ? (
          <List.Item>
            Give the assistant {count(writeTools.length, 'tool')} that change things:{' '}
            {writeTools.map((t) => t.name).join(', ')}.
          </List.Item>
        ) : null}
        {c.permissions.length > 0 ? (
          <List.Item>
            Define {count(c.permissions.length, 'permission')} you can grant through roles:{' '}
            {c.permissions.map((p) => (p.description ? `${p.action} (${p.description})` : p.action)).join(', ')}.
          </List.Item>
        ) : null}
        {c.settingKeys.length > 0 ? (
          <List.Item>Keep {count(c.settingKeys.length, 'setting')} of its own.</List.Item>
        ) : null}
        {c.streamTopics.length > 0 ? <List.Item>Send live updates to open screens.</List.Item> : null}
        {c.identityProviders.length > 0 ? (
          <List.Item>
            <Text span fw={700} className={styles.warning}>
              Receive the passwords users type to sign in with {c.identityProviders.map((p) => p.label).join(', ')}
            </Text>
            , and decide who they are signed in as.
          </List.Item>
        ) : null}
        {changes > 0 ? (
          <List.Item>
            {plan.fromVersion ? 'Change its own database schema' : 'Create its own database schema'} with{' '}
            {count(changes, 'change')}.
          </List.Item>
        ) : null}
        {info.requires.length > 0 ? <List.Item>Depend on {info.requires.join(', ')}.</List.Item> : null}
        {info.requiresLicense ? (
          <List.Item>Need a license file, which you upload under its License tab once it is installed.</List.Item>
        ) : null}
      </List>
    </Section>
  );
}

/** What an update adds or takes away, and which roles lose a permission. */
function ChangesSection({ plan }: Readonly<{ plan: PluginPlanView }>) {
  const d = plan.diff;
  const rolesLosing = Object.entries(plan.rolesLosingPermission).filter(([, roles]) => roles > 0);
  return (
    <Section title="What changes">
      <List size="sm" spacing={2}>
        {d.permissionsAdded.map((p) => (
          <List.Item key={`adds-${p}`}>
            <Text span fw={700} className={styles.warning}>
              Adds permission {p}
            </Text>
          </List.Item>
        ))}
        {[
          ...d.permissionsRemoved.map((p) => `Removes permission ${p}`),
          ...d.mcpToolsAdded.map((t) => `Adds assistant tool ${t}`),
          ...d.mcpToolsRemoved.map((t) => `Removes assistant tool ${t}`),
          ...d.settingKeysAdded.map((k) => `Adds setting ${k}`),
          ...d.settingKeysRemoved.map((k) => `Removes setting ${k}`),
          ...d.streamTopicsAdded.map((t) => `Adds live topic ${t}`),
          ...d.streamTopicsRemoved.map((t) => `Removes live topic ${t}`),
          ...d.identityProvidersAdded.map((i) => `Adds sign-in ${i}`),
          ...d.identityProvidersRemoved.map((i) => `Removes sign-in ${i}`),
        ].map((line) => (
          <List.Item key={line}>{line}</List.Item>
        ))}
        {rolesLosing.map(([permission, roles]) => (
          <List.Item key={`roles-${permission}`}>
            <span className={styles.warning}>
              {count(roles, 'role')} {roles === 1 ? 'grants' : 'grant'} {permission}, which this version removes; those
              roles lose it.
            </span>
          </List.Item>
        ))}
      </List>
      {plan.info.changeNotes ? (
        <Text size="sm" className={styles.notes}>
          {plan.info.changeNotes}
        </Text>
      ) : null}
    </Section>
  );
}

/** What confirming will interrupt, and what cannot be undone afterwards. */
function ConfirmSection({ plan }: Readonly<{ plan: PluginPlanView }>) {
  const [sqlOpen, sql] = useDisclosure(false);
  const changes = plan.pendingChangesets.length;
  return (
    <Section title="What happens when you confirm">
      <Text size="sm">{downtime(plan)}</Text>
      {changes > 0 ? (
        <>
          {!plan.reversible ? (
            <Text size="sm" className={styles.warning}>
              Irreversible: {count(plan.pendingChangesets.filter((cs) => !cs.reversible).length, 'database change')}{' '}
              cannot be undone, so Roll back will not be available afterwards. Take a database backup first.
            </Text>
          ) : null}
          <Group gap="xs">
            <Anchor component="button" type="button" size="sm" onClick={sql.toggle} aria-expanded={sqlOpen}>
              {sqlOpen ? 'Hide the SQL' : 'Show the SQL'}
            </Anchor>
          </Group>
          <Collapse expanded={sqlOpen}>
            <CodeHighlight code={plan.updateSql.trimEnd()} language="sql" />
          </Collapse>
        </>
      ) : null}
    </Section>
  );
}

/**
 * "What this plugin will be able to do" (design.md §8): every capability as a sentence, what an
 * update adds or takes away, and what confirming will interrupt — all before anything is armed.
 */
export function PlanReview({
  plan,
  warnings = [],
  trust,
}: Readonly<{
  plan: PluginPlanView;
  warnings?: PluginViolationView[];
  /** Offered for an untrusted key: whether this user may trust it, and what happens when they choose to. */
  trust?: TrustOffer;
}>) {
  const info = plan.info;
  const update = !!plan.fromVersion && plan.fromVersion !== plan.toVersion;

  return (
    <Stack gap="md">
      <Stack gap={2}>
        <Text fw={600}>
          {info.title} {plan.toVersion}
          {update ? (
            <Text component="span" c="dimmed">
              {' '}
              (installed: {plan.fromVersion})
            </Text>
          ) : null}
        </Text>
        <Text size="sm" c="dimmed">
          by {info.vendor.name}
          {info.license ? ` · ${info.license}` : ''} · supports Studio {info.since}
          {info.until ? ` to ${info.until}` : ' and later'}
        </Text>
        {info.description ? <Text size="sm">{info.description}</Text> : null}
      </Stack>

      {plan.missingRequires.length > 0 ? (
        <Alert variant="light" color="red" title="It cannot be activated yet">
          It requires {plan.missingRequires.join(', ')}, which {plan.missingRequires.length === 1 ? 'is' : 'are'} not
          active. Install or enable {plan.missingRequires.length === 1 ? 'it' : 'them'} first.
        </Alert>
      ) : null}

      <PublisherSection plan={plan} trust={trust} />

      <CapabilitiesSection plan={plan} />

      {update ? <ChangesSection plan={plan} /> : null}

      <ConfirmSection plan={plan} />

      {warnings.length > 0 ? (
        <Section title="Worth knowing">
          <List size="sm" spacing={2}>
            {warnings.map((w) => (
              <List.Item key={w.code + w.message}>{w.message}</List.Item>
            ))}
          </List>
        </Section>
      ) : null}
    </Stack>
  );
}

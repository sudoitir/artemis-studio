import { useMemo, useState } from 'react';
import { Button, Checkbox, Code, CopyButton, Drawer, Group, List, Stack, Tabs, Text } from '@mantine/core';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { ConfirmAction } from './ConfirmAction.tsx';
import { LicenseTab } from './LicenseTab.tsx';
import {
  useLifecycle,
  usePluginHistory,
  usePurge,
  usePurgePlan,
  violationsOf,
  type LifecycleAction,
  type PluginView,
} from './api.ts';
import { bytes, dataColumns, historyColumns } from './drawerColumns.tsx';
import styles from './Plugins.module.css';
import { UnverifiedBadge } from './UnverifiedBadge.tsx';
import { STATUS, count, licenseNeedsAction } from './words.ts';

type Pending = LifecycleAction | 'purge' | null;

const VERBS: Record<Exclude<Pending, null>, ActionVerb> = {
  enable: { verb: 'Enable', past: 'Enabled', progressive: 'Enabling' },
  rollback: { verb: 'Roll back', past: 'Rolled back', progressive: 'Rolling back' },
  disable: { verb: 'Disable', past: 'Disabled', progressive: 'Disabling' },
  uninstall: { verb: 'Uninstall', past: 'Uninstalled', progressive: 'Uninstalling' },
  purge: { verb: 'Purge', past: 'Purged', progressive: 'Purging' },
};

type PluginInfo = NonNullable<PluginView['info']>;

/** Only an `http(s)` vendor link is ever made a link, and it never gets the opener. */
function VendorLink({ url }: { url: string | null | undefined }) {
  if (!url) return null;
  const safe = /^https?:\/\//i.test(url);
  return safe ? (
    <a href={url} target="_blank" rel="noopener noreferrer" className={linkClasses.link}>
      {url}
    </a>
  ) : (
    <Text size="sm">{url}</Text>
  );
}

/** Who signed the jar, and with which key; a key nobody trusts is said to be so. */
function signerItems(plugin: PluginView): DescriptionItem[] {
  const fingerprint = plugin.signerFingerprint;
  const trustNote = plugin.verified ? '' : ' (not a trusted key)';
  const items: DescriptionItem[] = [
    {
      term: 'Signed by',
      value: fingerprint ? `${plugin.signerSubject ?? 'An unnamed certificate'}${trustNote}` : 'Not signed',
    },
  ];
  if (fingerprint) {
    items.push({
      term: 'Key fingerprint',
      value: (
        <Group gap="xs" wrap="nowrap">
          <Code className={styles.fingerprint}>{fingerprint}</Code>
          <CopyButton value={fingerprint}>
            {({ copied, copy }) => (
              <Button size="compact-xs" variant="subtle" onClick={copy} aria-label="Copy fingerprint">
                {copied ? 'Copied' : 'Copy'}
              </Button>
            )}
          </CopyButton>
        </Group>
      ),
    });
  }
  return items;
}

/** What the plugin is and who put it there. */
function OverviewTab({ plugin, info }: Readonly<{ plugin: PluginView; info: PluginInfo }>) {
  useDisplayZone();
  const supportedUntil = info.until ? ` to ${info.until}` : ' and later';
  const installedBy = plugin.installedBy ? ` by ${plugin.installedBy}` : '';
  const items: DescriptionItem[] = [
    {
      term: 'Vendor',
      value: (
        <Stack gap={0}>
          <Text size="sm">{info.vendor.name}</Text>
          <VendorLink url={info.vendor.url} />
          {info.vendor.email ? <Text size="sm">{info.vendor.email}</Text> : null}
        </Stack>
      ),
    },
    { term: 'Supports Studio', value: `${info.since}${supportedUntil}` },
    {
      term: 'Installed',
      value: `${absoluteLabel(plugin.installedAt)}${installedBy}`,
    },
    ...(plugin.activatedAt ? [{ term: 'Last activated', value: absoluteLabel(plugin.activatedAt) }] : []),
    {
      term: 'Artifact sha256',
      value: (
        <Group gap="xs" wrap="nowrap">
          <Code className={styles.num}>{plugin.sha256.slice(0, 16)}…</Code>
          <CopyButton value={plugin.sha256}>
            {({ copied, copy }) => (
              <Button size="compact-xs" variant="subtle" onClick={copy}>
                {copied ? 'Copied' : 'Copy'}
              </Button>
            )}
          </CopyButton>
        </Group>
      ),
    },
    ...signerItems(plugin),
    ...(info.license ? [{ term: 'License', value: info.license }] : []),
  ];
  return (
    <Stack gap="sm">
      <Group gap="xs">
        <Text size="sm">
          <b>{STATUS[plugin.status] ?? plugin.status}</b>
          {plugin.failure ? ` — ${plugin.failure}` : ''}
        </Text>
        {plugin.verified ? null : <UnverifiedBadge />}
      </Group>
      {info.description ? <Text size="sm">{info.description}</Text> : null}
      <DescriptionList items={items} label="About this plugin" />
      {info.changeNotes ? (
        <Section title="Change notes" headingLevel={3}>
          <Text size="sm" className={styles.notes}>
            {info.changeNotes}
          </Text>
        </Section>
      ) : null}
    </Stack>
  );
}

/** Everything the plugin adds to Studio. */
function ContributionsTab({ plugin, info }: Readonly<{ plugin: PluginView; info: PluginInfo }>) {
  return (
    <List size="sm" spacing={4}>
      <List.Item>{info.contributions.ui ? 'Screens in Studio' : 'No screens of its own'}</List.Item>
      {info.contributions.permissions.map((p) => (
        <List.Item key={p.action}>
          Permission <Code>{p.action}</Code>
          {p.description ? ` — ${p.description}` : ''}
        </List.Item>
      ))}
      {info.contributions.mcpTools.map((t) => (
        <List.Item key={t.name}>
          Assistant tool <Code>{t.name}</Code> ({t.posture === 'read' ? 'reads' : 'changes things'})
          {t.description ? ` — ${t.description}` : ''}
        </List.Item>
      ))}
      {info.contributions.settingKeys.map((k) => (
        <List.Item key={k}>
          Setting <Code>{k}</Code>
        </List.Item>
      ))}
      {info.contributions.streamTopics.map((t) => (
        <List.Item key={t}>
          Live topic <Code>{t}</Code>
        </List.Item>
      ))}
      {info.contributions.identityProviders.map((i) => (
        <List.Item key={i.id}>
          Sign-in <Code>{i.id}</Code> — {i.label}; receives the passwords typed for it
        </List.Item>
      ))}
      {info.requires.length > 0 ? <List.Item>Requires {info.requires.join(', ')}</List.Item> : null}
      {plugin.dependants.length > 0 ? <List.Item>Required by {plugin.dependants.join(', ')}</List.Item> : null}
    </List>
  );
}

/** The schema and tables the plugin keeps, measured. */
function DataTab({ purgePlan }: Readonly<{ purgePlan: ReturnType<typeof usePurgePlan> }>) {
  const columns = useMemo(() => dataColumns(), []);
  if (purgePlan.isPending) return <LoadingState label="Measuring its data" blockSize="8rem" />;
  if (!purgePlan.data) {
    return <ErrorState error={purgePlan.error} onRetry={() => void purgePlan.refetch()} />;
  }
  return (
    <Stack gap="xs">
      <Text size="sm">
        Its data lives in schema <Code>{purgePlan.data.schema}</Code>, which only it uses. Figures are estimates from
        table statistics.
      </Text>
      <DataTable
        variant="static"
        label="Tables the plugin keeps"
        columns={columns}
        data={purgePlan.data.tables}
        rowKey={(t) => t.name}
        storageKey="plugins.data"
        height={{ maxRows: 12 }}
        empty={
          <EmptyState
            kind="empty"
            title="No tables"
            description="This plugin keeps no tables of its own, so there is no data of it to purge."
          />
        }
      />
    </Stack>
  );
}

/** Everything done to the plugin, newest first. */
function HistoryTab({ history }: Readonly<{ history: ReturnType<typeof usePluginHistory> }>) {
  useDisplayZone();
  const columns = useMemo(() => historyColumns(), []);
  return (
    <DataTable
      variant="static"
      label="History of the plugin"
      columns={columns}
      data={history.data ?? []}
      rowKey={(e) => String(e.id)}
      storageKey="plugins.history"
      height={{ maxRows: 12 }}
      loading={history.isPending}
      error={history.isError ? <ErrorState error={history.error} onRetry={() => void history.refetch()} /> : undefined}
      empty={
        <EmptyState
          kind="empty"
          title="Nothing recorded yet"
          description="Every install, update, enable, disable and removal of this plugin is recorded here, with who did it."
        />
      }
    />
  );
}

/** Whether the server asked for a confirmation, whether it is ticked, and whether it was skipped. */
type AcknowledgementState = Readonly<{
  needed: boolean;
  checked: boolean;
  missing: boolean;
  onChange: (checked: boolean) => void;
}>;

type Action = { key: Exclude<Pending, null>; label: string; show: boolean };

/** What can be done to the plugin; each button opens its confirmation. */
function ActionsTab({
  plugin,
  info,
  canInstall,
  cannotInstall,
  actions,
  onUpdate,
  onPick,
}: Readonly<{
  plugin: PluginView;
  info: PluginInfo;
  canInstall: boolean;
  cannotInstall?: string;
  actions: Action[];
  onUpdate: (id: string) => void;
  onPick: (key: Exclude<Pending, null>) => void;
}>) {
  return (
    <Stack gap="sm">
      {!canInstall ? (
        <Text size="sm" c="dimmed">
          {cannotInstall ?? 'Only someone who can install plugins can change this one.'}
        </Text>
      ) : null}
      {info.updateUrl && plugin.status !== 'uninstalled' ? (
        <Button variant="default" className={styles.start} disabled={!canInstall} onClick={() => onUpdate(plugin.id)}>
          Check its update URL for a newer version
        </Button>
      ) : null}
      {actions
        .filter((a) => a.show)
        .map((a) => (
          <Button
            key={a.key}
            variant="default"
            className={styles.start}
            disabled={!canInstall}
            onClick={() => onPick(a.key)}
          >
            {a.label}…
          </Button>
        ))}
    </Stack>
  );
}

/** The label of the enable confirmation: roll back, retry a failed start, or enable. */
function enableLabel(pending: Pending, plugin: PluginView): string {
  if (pending === 'rollback') return 'Roll back';
  return plugin.status === 'failed' ? 'Retry' : 'Enable';
}

/** The confirmations behind the actions: each states what it affects before it can be armed. */
function Confirmations({
  plugin,
  info,
  pending,
  onCancel,
  lifecycle,
  purge,
  purgePlan,
  cascade,
  onCascade,
  acknowledgement,
  onRun,
  onPurged,
}: Readonly<{
  plugin: PluginView;
  info: PluginInfo;
  pending: Pending;
  onCancel: () => void;
  lifecycle: ReturnType<typeof useLifecycle>;
  purge: ReturnType<typeof usePurge>;
  purgePlan: ReturnType<typeof usePurgePlan>;
  cascade: boolean;
  onCascade: (on: boolean) => void;
  acknowledgement: AcknowledgementState;
  onRun: (action: LifecycleAction) => void;
  onPurged: () => void;
}>) {
  return (
    <>
      <ConfirmAction
        opened={pending === 'enable' || pending === 'rollback'}
        onClose={onCancel}
        title={pending === 'rollback' ? `Roll back ${info.title}` : `Start ${info.title} again`}
        confirmLabel={enableLabel(pending, plugin)}
        pending={lifecycle.isPending}
        error={lifecycle.error}
        onConfirm={() => onRun(pending as LifecycleAction)}
      >
        <Text size="sm">
          {pending === 'rollback'
            ? `Reactivates the version that ran before ${plugin.version}. The current version changed no database, so nothing is lost; ${info.title} does not pause.`
            : `Starts ${info.title} ${plugin.version} again for everyone.`}
        </Text>
        {acknowledgement.needed ? (
          <Checkbox
            checked={acknowledgement.checked}
            onChange={(e) => acknowledgement.onChange(e.currentTarget.checked)}
            label="I have read the reason above and want to continue"
            error={acknowledgement.missing ? 'Tick this to continue.' : undefined}
          />
        ) : null}
      </ConfirmAction>

      <ConfirmAction
        opened={pending === 'disable' || pending === 'uninstall'}
        onClose={onCancel}
        title={pending === 'uninstall' ? `Uninstall ${info.title}` : `Disable ${info.title}`}
        confirmLabel={pending === 'uninstall' ? `Uninstall ${info.title}` : `Disable ${info.title}`}
        typeToConfirm={pending === 'uninstall' ? plugin.id : undefined}
        danger={pending === 'uninstall'}
        pending={lifecycle.isPending}
        error={lifecycle.error}
        onConfirm={() => onRun(pending as LifecycleAction)}
      >
        <Stack gap="xs">
          <Text size="sm">
            Its screens, API, assistant tools and background jobs stop for everyone; work in flight finishes first.
            {pending === 'uninstall'
              ? ' Its data is kept: installing it again picks it up, and only Purge deletes it.'
              : ' Enable brings it back as it was.'}
          </Text>
          {plugin.dependants.length > 0 ? (
            <Checkbox
              checked={cascade}
              onChange={(e) => onCascade(e.currentTarget.checked)}
              label={`Also disable what requires it: ${plugin.dependants.join(', ')}`}
              description="Without this, it is refused while they are active."
            />
          ) : null}
        </Stack>
      </ConfirmAction>

      <ConfirmAction
        opened={pending === 'purge'}
        onClose={onCancel}
        title={`Purge ${info.title}'s data`}
        confirmLabel="Delete its data permanently"
        typeToConfirm={plugin.id}
        danger
        pending={purge.isPending}
        error={purge.error}
        onConfirm={() =>
          purge.mutate(plugin.id, {
            onSuccess: () => {
              notify.succeeded({ action: VERBS.purge, subject: `the data of ${info.title}` });
              onPurged();
            },
          })
        }
      >
        {purgePlan.data ? (
          <Stack gap="xs">
            <Text size="sm">This cannot be undone. It deletes:</Text>
            <List size="sm">
              <List.Item>
                Schema <Code>{purgePlan.data.schema}</Code>:{' '}
                {count(purgePlan.data.tables.length, 'table') ?? 'no tables'}, about{' '}
                {purgePlan.data.tables.reduce((n, t) => n + Math.max(t.estimatedRows, 0), 0).toLocaleString()} rows,{' '}
                {bytes(purgePlan.data.tables.reduce((n, t) => n + t.bytes, 0))}
              </List.Item>
              <List.Item>{count(purgePlan.data.grants, 'role grant') ?? 'no role grants'} of its permissions</List.Item>
              <List.Item>{count(purgePlan.data.settings, 'saved setting') ?? 'no saved settings'}</List.Item>
              <List.Item>{count(purgePlan.data.artifacts, 'stored jar') ?? 'no stored jars'}</List.Item>
            </List>
          </Stack>
        ) : (
          <Text size="sm">
            {purgePlan.error ? `The estimate is unavailable: ${purgePlan.error.message}` : 'Estimating…'}
          </Text>
        )}
      </ConfirmAction>
    </>
  );
}

/**
 * The open tab. A plugin whose license needs someone opens on it; the tab someone picks is kept for
 * that plugin only, so opening another plugin starts afresh.
 */
function useDrawerTab(plugin: PluginView | undefined): [string, (next: string | null) => void] {
  const [chosen, setChosen] = useState<{ id: string; tab: string } | null>(null);
  const fallback = licenseNeedsAction(plugin?.license) ? 'license' : 'overview';
  const tab = chosen?.id === plugin?.id ? chosen?.tab : undefined;
  return [tab ?? fallback, (next) => plugin && next && setChosen({ id: plugin.id, tab: next })];
}

/**
 * One plugin, in full (design.md §8), at `?plugin=<id>`: what it is and who put it there, what it
 * adds, what data it keeps, everything done to it, and the actions that change it — each stating
 * what it affects before it can be confirmed.
 */
export function PluginDrawer({
  plugin,
  canInstall,
  cannotInstall,
  onClose,
  onUpdate,
}: Readonly<{
  plugin: PluginView | undefined;
  canInstall: boolean;
  cannotInstall?: string;
  onClose: () => void;
  onUpdate: (id: string) => void;
}>) {
  const [pending, setPending] = useState<Pending>(null);
  const [cascade, setCascade] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [ackMissing, setAckMissing] = useState(false);
  const [tab, setTab] = useDrawerTab(plugin);
  const lifecycle = useLifecycle();
  const purge = usePurge();
  const history = usePluginHistory(plugin?.id);
  const purgePlan = usePurgePlan(plugin?.id, !!plugin && (tab === 'data' || pending === 'purge'));
  const info = plugin?.info;

  // Enable and roll back are first tried plainly; when the server says they need confirming, it says
  // why (its message is shown) and a tick is required before they are sent again with `acknowledge`.
  const needsAcknowledgement = violationsOf(lifecycle.error).some((v) => v.code === 'acknowledgement-required');

  const run = (action: LifecycleAction) => {
    if (needsAcknowledgement && !acknowledged) {
      setAckMissing(true);
      return;
    }
    if (plugin) {
      lifecycle.mutate(
        { id: plugin.id, action, cascade, acknowledge: acknowledged },
        {
          onSuccess: () => {
            notify.succeeded({ action: VERBS[action], subject: `plugin ${plugin.info.title}` });
            setPending(null);
          },
        },
      );
    }
  };

  const actions: { key: Exclude<Pending, null>; label: string; show: boolean }[] = plugin
    ? [
        {
          key: 'enable',
          label: plugin.status === 'failed' ? 'Retry' : 'Enable',
          show: ['disabled', 'failed'].includes(plugin.status),
        },
        { key: 'rollback', label: 'Roll back to the previous version', show: plugin.rollbackAvailable },
        { key: 'disable', label: 'Disable', show: ['active', 'needs_restart'].includes(plugin.status) },
        {
          key: 'uninstall',
          label: 'Uninstall',
          show: plugin.status !== 'uninstalled' && plugin.status !== 'activating',
        },
        { key: 'purge', label: 'Purge its data', show: plugin.status === 'uninstalled' },
      ]
    : [];

  return (
    <Drawer
      opened={!!plugin}
      onClose={onClose}
      // One Escape closes one layer: while a confirmation is open, it closes that, not the drawer.
      closeOnEscape={pending === null}
      position="right"
      size="lg"
      title={info ? `${info.title} ${plugin?.version}` : ''}
    >
      {plugin && info ? (
        <Tabs value={tab} onChange={setTab}>
          <Tabs.List>
            <Tabs.Tab value="overview">Overview</Tabs.Tab>
            {plugin.license ? <Tabs.Tab value="license">License</Tabs.Tab> : null}
            <Tabs.Tab value="contributions">Contributions</Tabs.Tab>
            <Tabs.Tab value="data">Data</Tabs.Tab>
            <Tabs.Tab value="history">History</Tabs.Tab>
            <Tabs.Tab value="actions">Actions</Tabs.Tab>
          </Tabs.List>

          <Tabs.Panel value="overview" pt="md">
            <OverviewTab plugin={plugin} info={info} />
          </Tabs.Panel>

          {plugin.license ? (
            <Tabs.Panel value="license" pt="md">
              <LicenseTab
                plugin={plugin}
                info={info}
                license={plugin.license}
                canInstall={canInstall}
                cannotInstall={cannotInstall}
              />
            </Tabs.Panel>
          ) : null}

          <Tabs.Panel value="contributions" pt="md">
            <ContributionsTab plugin={plugin} info={info} />
          </Tabs.Panel>

          <Tabs.Panel value="data" pt="md">
            <DataTab purgePlan={purgePlan} />
          </Tabs.Panel>

          <Tabs.Panel value="history" pt="md">
            <HistoryTab history={history} />
          </Tabs.Panel>

          <Tabs.Panel value="actions" pt="md">
            <ActionsTab
              plugin={plugin}
              info={info}
              canInstall={canInstall}
              cannotInstall={cannotInstall}
              actions={actions}
              onUpdate={onUpdate}
              onPick={(key) => {
                lifecycle.reset();
                purge.reset();
                setCascade(false);
                setAcknowledged(false);
                setAckMissing(false);
                setPending(key);
              }}
            />
          </Tabs.Panel>
        </Tabs>
      ) : null}

      {plugin && info ? (
        <Confirmations
          plugin={plugin}
          info={info}
          pending={pending}
          onCancel={() => setPending(null)}
          lifecycle={lifecycle}
          purge={purge}
          purgePlan={purgePlan}
          cascade={cascade}
          onCascade={setCascade}
          acknowledgement={{
            needed: needsAcknowledgement,
            checked: acknowledged,
            missing: ackMissing,
            onChange: (checked) => {
              setAcknowledged(checked);
              setAckMissing(false);
            },
          }}
          onRun={run}
          onPurged={() => {
            setPending(null);
            onClose();
          }}
        />
      ) : null}
    </Drawer>
  );
}

import { useState } from 'react';
import {
  Anchor,
  Button,
  Checkbox,
  Code,
  CopyButton,
  Drawer,
  Group,
  List,
  Loader,
  Stack,
  Table,
  Tabs,
  Text,
  Title,
} from '@mantine/core';

import { ConfirmAction } from './ConfirmAction.tsx';
import {
  useLifecycle,
  usePluginHistory,
  usePurge,
  usePurgePlan,
  type LifecycleAction,
  type PluginView,
} from './api.ts';
import styles from './Plugins.module.css';
import { STATUS, count } from './words.ts';

type Pending = LifecycleAction | 'purge' | null;

function bytes(n: number): string {
  if (n >= 1024 * 1024 * 1024) return `${(n / 1024 / 1024 / 1024).toFixed(1)} GB`;
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${Math.ceil(n / 1024)} KB`;
}

/** Only an `http(s)` vendor link is ever made a link, and it never gets the opener. */
function VendorLink({ url }: { url: string | null | undefined }) {
  if (!url) return null;
  const safe = /^https?:\/\//i.test(url);
  return safe ? (
    <Anchor href={url} target="_blank" rel="noopener noreferrer" size="sm">
      {url}
    </Anchor>
  ) : (
    <Text size="sm">{url}</Text>
  );
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
}: {
  plugin: PluginView | undefined;
  canInstall: boolean;
  cannotInstall?: string;
  onClose: () => void;
  onUpdate: (id: string) => void;
}) {
  const [tab, setTab] = useState<string | null>('overview');
  const [pending, setPending] = useState<Pending>(null);
  const [cascade, setCascade] = useState(false);
  const lifecycle = useLifecycle();
  const purge = usePurge();
  const history = usePluginHistory(plugin?.id);
  const purgePlan = usePurgePlan(plugin?.id, !!plugin && (tab === 'data' || pending === 'purge'));
  const info = plugin?.info;

  const run = (action: LifecycleAction) =>
    plugin && lifecycle.mutate({ id: plugin.id, action, cascade }, { onSuccess: () => setPending(null) });

  const actions: { key: Exclude<Pending, null>; label: string; show: boolean }[] = plugin
    ? [
        { key: 'enable', label: plugin.status === 'failed' ? 'Retry' : 'Enable', show: ['disabled', 'failed'].includes(plugin.status) },
        { key: 'rollback', label: 'Roll back to the previous version', show: plugin.rollbackAvailable },
        { key: 'disable', label: 'Disable', show: ['active', 'needs_restart'].includes(plugin.status) },
        { key: 'uninstall', label: 'Uninstall', show: plugin.status !== 'uninstalled' && plugin.status !== 'activating' },
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
            <Tabs.Tab value="contributions">Contributions</Tabs.Tab>
            <Tabs.Tab value="data">Data</Tabs.Tab>
            <Tabs.Tab value="history">History</Tabs.Tab>
            <Tabs.Tab value="actions">Actions</Tabs.Tab>
          </Tabs.List>

          <Tabs.Panel value="overview" pt="md">
            <Stack gap="sm">
              <Text size="sm">
                <b>{STATUS[plugin.status] ?? plugin.status}</b>
                {plugin.failure ? ` — ${plugin.failure}` : ''}
              </Text>
              {info.description ? <Text size="sm">{info.description}</Text> : null}
              <Table variant="vertical" withTableBorder>
                <Table.Tbody>
                  <Table.Tr>
                    <Table.Th w={160}>Vendor</Table.Th>
                    <Table.Td>
                      <Stack gap={0}>
                        <Text size="sm">{info.vendor.name}</Text>
                        <VendorLink url={info.vendor.url} />
                        {info.vendor.email ? <Text size="sm">{info.vendor.email}</Text> : null}
                      </Stack>
                    </Table.Td>
                  </Table.Tr>
                  <Table.Tr>
                    <Table.Th>Supports Studio</Table.Th>
                    <Table.Td>
                      {info.since}
                      {info.until ? ` to ${info.until}` : ' and later'}
                    </Table.Td>
                  </Table.Tr>
                  <Table.Tr>
                    <Table.Th>Installed</Table.Th>
                    <Table.Td>
                      {new Date(plugin.installedAt).toLocaleString()}
                      {plugin.installedBy ? ` by ${plugin.installedBy}` : ''}
                    </Table.Td>
                  </Table.Tr>
                  {plugin.activatedAt ? (
                    <Table.Tr>
                      <Table.Th>Last activated</Table.Th>
                      <Table.Td>{new Date(plugin.activatedAt).toLocaleString()}</Table.Td>
                    </Table.Tr>
                  ) : null}
                  <Table.Tr>
                    <Table.Th>Artifact sha256</Table.Th>
                    <Table.Td>
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
                    </Table.Td>
                  </Table.Tr>
                  {info.license ? (
                    <Table.Tr>
                      <Table.Th>License</Table.Th>
                      <Table.Td>{info.license}</Table.Td>
                    </Table.Tr>
                  ) : null}
                </Table.Tbody>
              </Table>
              {info.changeNotes ? (
                <>
                  <Title order={4} fz="sm">
                    Change notes
                  </Title>
                  <Text size="sm" className={styles.notes}>
                    {info.changeNotes}
                  </Text>
                </>
              ) : null}
            </Stack>
          </Tabs.Panel>

          <Tabs.Panel value="contributions" pt="md">
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
              {info.requires.length > 0 ? <List.Item>Requires {info.requires.join(', ')}</List.Item> : null}
              {plugin.dependants.length > 0 ? <List.Item>Required by {plugin.dependants.join(', ')}</List.Item> : null}
            </List>
          </Tabs.Panel>

          <Tabs.Panel value="data" pt="md">
            {purgePlan.isPending ? (
              <Loader size="sm" />
            ) : purgePlan.data ? (
              <Stack gap="xs">
                <Text size="sm">
                  Its data lives in schema <Code>{purgePlan.data.schema}</Code>, which only it uses. Figures are estimates from
                  table statistics.
                </Text>
                {purgePlan.data.tables.length === 0 ? (
                  <Text size="sm" c="dimmed">
                    No tables.
                  </Text>
                ) : (
                  <Table>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Table</Table.Th>
                        <Table.Th ta="end">Rows (about)</Table.Th>
                        <Table.Th ta="end">Size</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {purgePlan.data.tables.map((t) => (
                        <Table.Tr key={t.name}>
                          <Table.Td>{t.name}</Table.Td>
                          <Table.Td ta="end" className={styles.num}>
                            {t.estimatedRows < 0 ? 'not yet counted' : t.estimatedRows.toLocaleString()}
                          </Table.Td>
                          <Table.Td ta="end" className={styles.num}>
                            {bytes(t.bytes)}
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                )}
              </Stack>
            ) : (
              <Text size="sm" c="dimmed">
                {purgePlan.error ? `Its data could not be measured: ${purgePlan.error.message}` : ''}
              </Text>
            )}
          </Tabs.Panel>

          <Tabs.Panel value="history" pt="md">
            {history.isPending ? (
              <Loader size="sm" />
            ) : (history.data ?? []).length === 0 ? (
              <Text size="sm" c="dimmed">
                Nothing recorded yet.
              </Text>
            ) : (
              <Table>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>When</Table.Th>
                    <Table.Th>Who</Table.Th>
                    <Table.Th>What</Table.Th>
                    <Table.Th>Outcome</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {(history.data ?? []).map((e) => (
                    <Table.Tr key={e.id}>
                      <Table.Td className={styles.num}>{new Date(e.ts).toLocaleString()}</Table.Td>
                      <Table.Td>{e.username ?? '—'}</Table.Td>
                      <Table.Td>{e.action.replace(/^PLUGIN_/, '').replace(/_/g, ' ').toLowerCase()}</Table.Td>
                      <Table.Td>
                        <span className={e.outcome === 'FAILED' ? styles.danger : undefined}>{e.outcome.toLowerCase()}</span>
                        {e.error ? (
                          <Text size="xs" c="dimmed">
                            {e.error}
                          </Text>
                        ) : null}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Tabs.Panel>

          <Tabs.Panel value="actions" pt="md">
            <Stack gap="sm">
              {!canInstall ? (
                <Text size="sm" c="dimmed">
                  {cannotInstall ?? 'Only someone who can install plugins can change this one.'}
                </Text>
              ) : null}
              {info.updateUrl && plugin.status !== 'uninstalled' ? (
                <Button variant="default" w="fit-content" disabled={!canInstall} onClick={() => onUpdate(plugin.id)}>
                  Check its update URL for a newer version
                </Button>
              ) : null}
              {actions
                .filter((a) => a.show)
                .map((a) => (
                  <Button
                    key={a.key}
                    variant={a.key === 'purge' || a.key === 'uninstall' ? 'outline' : 'default'}
                    color={a.key === 'purge' || a.key === 'uninstall' ? 'red' : undefined}
                    w="fit-content"
                    disabled={!canInstall}
                    onClick={() => {
                      lifecycle.reset();
                      purge.reset();
                      setCascade(false);
                      setPending(a.key);
                    }}
                  >
                    {a.label}…
                  </Button>
                ))}
            </Stack>
          </Tabs.Panel>
        </Tabs>
      ) : null}

      {plugin && info ? (
        <>
          <ConfirmAction
            opened={pending === 'enable' || pending === 'rollback'}
            onClose={() => setPending(null)}
            title={pending === 'rollback' ? `Roll back ${info.title}` : `Start ${info.title} again`}
            confirmLabel={pending === 'rollback' ? 'Roll back' : plugin.status === 'failed' ? 'Retry' : 'Enable'}
            pending={lifecycle.isPending}
            error={lifecycle.error}
            onConfirm={() => run(pending as LifecycleAction)}
          >
            <Text size="sm">
              {pending === 'rollback'
                ? `Reactivates the version that ran before ${plugin.version}. The current version changed no database, so nothing is lost; ${info.title} does not pause.`
                : `Starts ${info.title} ${plugin.version} again for everyone.`}
            </Text>
          </ConfirmAction>

          <ConfirmAction
            opened={pending === 'disable' || pending === 'uninstall'}
            onClose={() => setPending(null)}
            title={pending === 'uninstall' ? `Uninstall ${info.title}` : `Disable ${info.title}`}
            confirmLabel={pending === 'uninstall' ? `Uninstall ${info.title}` : `Disable ${info.title}`}
            typeToConfirm={pending === 'uninstall' ? plugin.id : undefined}
            danger={pending === 'uninstall'}
            pending={lifecycle.isPending}
            error={lifecycle.error}
            onConfirm={() => run(pending as LifecycleAction)}
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
                  onChange={(e) => setCascade(e.currentTarget.checked)}
                  label={`Also disable what requires it: ${plugin.dependants.join(', ')}`}
                  description="Without this, it is refused while they are active."
                />
              ) : null}
            </Stack>
          </ConfirmAction>

          <ConfirmAction
            opened={pending === 'purge'}
            onClose={() => setPending(null)}
            title={`Purge ${info.title}'s data`}
            confirmLabel="Delete its data permanently"
            typeToConfirm={plugin.id}
            danger
            pending={purge.isPending}
            error={purge.error}
            onConfirm={() => purge.mutate(plugin.id, { onSuccess: () => { setPending(null); onClose(); } })}
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
              <Text size="sm">{purgePlan.error ? `The estimate is unavailable: ${purgePlan.error.message}` : 'Estimating…'}</Text>
            )}
          </ConfirmAction>
        </>
      ) : null}
    </Drawer>
  );
}

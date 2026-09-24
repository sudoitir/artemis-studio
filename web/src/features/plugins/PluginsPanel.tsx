import { useMemo, useState, type DragEvent } from 'react';
import { Alert, Anchor, Button, FileButton, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { useCheckUpdates, usePlugins, type PluginUpdateView, type PluginView } from './api.ts';
import { InstallDialog, type Source } from './InstallDialog.tsx';
import { InstallersDialog } from './InstallersDialog.tsx';
import { PluginDrawer } from './PluginDrawer.tsx';
import styles from './Plugins.module.css';
import { RestartControl } from './RestartControl.tsx';
import { GUIDE_URL, STATUS, contributionSummary, needsAttention, tone } from './words.ts';

const TEMPLATE_URL = `${branding.projectUrl}/tree/main/examples/plugin-template`;

function Mark({ plugin }: { plugin: PluginView }) {
  const [broken, setBroken] = useState(false);
  if (plugin.iconUrl && !broken) {
    // An <img>, never inline SVG: the server also sandboxes the icon (design.md §7).
    return <img src={plugin.iconUrl} alt="" className={styles.icon} onError={() => setBroken(true)} />;
  }
  return (
    <span className={styles.monogram} aria-hidden>
      {plugin.info.title.slice(0, 2).toUpperCase()}
    </span>
  );
}

/**
 * Administration → Plugins (design.md §8). Near-monochrome while everything is well; a plugin that
 * needs someone sorts to the top with its state in words and its fix in its row. The whole panel
 * takes a dropped jar; nothing is installed until the operator has reviewed and confirmed it.
 */
export function PluginsPanel() {
  const plugins = usePlugins();
  const checkUpdates = useCheckUpdates();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as { plugin?: string; upload?: string };
  const [source, setSource] = useState<Source | null>(() => (search.upload ? { kind: 'resume', sha: search.upload } : null));
  const [installersOpen, setInstallersOpen] = useState(false);
  const [dragging, setDragging] = useState(false);
  const view = plugins.data;

  const setSearch = (next: { plugin?: string; upload?: string }) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...next }), replace: true });

  const updates = useMemo(
    () => new Map((checkUpdates.data ?? []).map((u: PluginUpdateView) => [u.id, u])),
    [checkUpdates.data],
  );
  const rows = useMemo(
    () =>
      [...(view?.plugins ?? [])].sort(
        (a, b) => Number(needsAttention(b)) - Number(needsAttention(a)) || a.info.title.localeCompare(b.info.title),
      ),
    [view],
  );

  if (plugins.isPending) return <Loader size="sm" />;
  if (plugins.isError && !view) {
    return (
      <Alert color="red" variant="light" title="Plugins could not be listed">
        {plugins.error.status === 403
          ? 'Listing plugins needs the user:admin permission.'
          : `${plugins.error.message}. The list retries by itself.`}
      </Alert>
    );
  }
  if (!view) return null;

  const canInstall = view.canInstall && view.uploadEnabled;
  const cannotInstall = !view.uploadEnabled
    ? 'Installing and updating plugins is switched off on this installation (artemis-studio.plugins.upload.enabled=false).'
    : view.cannotInstall?.message;
  const attention = rows.filter(needsAttention);
  const open = rows.find((p) => p.id === search.plugin);

  const choose = (file: File | null) => {
    if (!file) return;
    setSource({ kind: 'file', file });
  };
  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    if (!canInstall) return;
    const file = [...event.dataTransfer.files].find((f) => f.name.endsWith('.jar'));
    choose(file ?? null);
  };

  // "acme-notes 1.0.0" labels, grouped per plugin: "acme-notes (1.0.0, 1.0.1)".
  const unreleased = new Map<string, Set<string>>();
  for (const label of view.restart.unreleased) {
    const [id, version] = label.split(' ');
    unreleased.set(id, (unreleased.get(id) ?? new Set()).add(version ?? ''));
  }
  const restartReasons = [
    ...rows.filter((p) => p.status === 'needs_restart').map((p) => `${p.info.title} starts after a restart.`),
    ...rows.filter((p) => p.stuck).map((p) => `${p.info.title} did not stop cleanly.`),
    ...[...unreleased].map(
      ([id, versions]) =>
        `Stopped versions of ${rows.find((p) => p.id === id)?.info.title ?? id} (${[...versions].join(', ')}) are still in memory; a restart frees it.`,
    ),
  ];

  const columns: GridColumn<PluginView>[] = [
    {
      id: 'plugin',
      header: 'Plugin',
      accessor: (p) => p.info.title,
      cell: (p) => (
        <Group gap="xs" wrap="nowrap">
          <Mark plugin={p} />
          <Text size="sm" truncate>
            {p.info.title}{' '}
            <Text span size="xs" c="dimmed">
              {p.info.vendor.name}
            </Text>
          </Text>
        </Group>
      ),
    },
    {
      id: 'version',
      header: 'Version',
      width: 170,
      accessor: (p) => p.version,
      cell: (p) => {
        const update = updates.get(p.id);
        return (
          <Text size="sm" className={styles.num}>
            {p.version}
            {update?.availableVersion ? (
              <Anchor component="button" size="xs" ml={6} onClick={() => setSource({ kind: 'update', id: p.id })}>
                {update.availableVersion} available
              </Anchor>
            ) : null}
          </Text>
        );
      },
    },
    {
      id: 'status',
      header: 'Status',
      width: 220,
      accessor: (p) => STATUS[p.status] ?? p.status,
      cell: (p) => {
        const t = tone(p);
        return (
          <Text size="sm" className={t ? styles[t] : undefined} truncate>
            {p.stuck ? 'Did not stop cleanly' : (STATUS[p.status] ?? p.status)}
            {p.status === 'activating' && p.progress ? ` · ${p.progress}` : ''}
          </Text>
        );
      },
    },
    { id: 'adds', header: 'Adds', accessor: (p) => contributionSummary(p.info) },
    {
      id: 'fix',
      header: '',
      width: 130,
      accessor: () => '',
      cell: (p) =>
        needsAttention(p) ? (
          <Button size="compact-xs" variant="default" onClick={() => setSearch({ plugin: p.id })}>
            {p.status === 'failed' ? 'See why' : p.status === 'incompatible' ? 'Update…' : 'Details'}
          </Button>
        ) : null,
    },
  ];

  return (
    <Stack
      gap="md"
      className={styles.drop}
      onDragOver={(e) => {
        if (!canInstall) return;
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target) setDragging(false);
      }}
      onDrop={onDrop}
    >
      {dragging ? (
        <div className={styles.overlay}>
          <Text fw={600}>Drop to inspect. Nothing is installed until you confirm.</Text>
        </div>
      ) : null}

      <Group justify="space-between" align="flex-start">
        <Stack gap={2}>
          <Title order={4}>Plugins</Title>
          <Text size="sm" c="dimmed" maw={640}>
            Plugins add screens, assistant tools and data of their own to {branding.productShortName}. A plugin runs
            inside {branding.productShortName} with its access, so only install ones you trust.{' '}
            <Anchor href={GUIDE_URL} target="_blank" rel="noopener noreferrer" size="sm">
              How plugins work
            </Anchor>
          </Text>
        </Stack>
        <Group gap="xs">
          <FileButton onChange={choose} accept=".jar,application/java-archive">
            {(props) => (
              <Button {...props} disabled={!canInstall}>
                Install plugin…
              </Button>
            )}
          </FileButton>
          <Button
            variant="default"
            disabled={!canInstall}
            loading={checkUpdates.isPending}
            onClick={() => checkUpdates.mutate()}
          >
            Check for updates
          </Button>
          {view.canInstall ? (
            <Button variant="subtle" onClick={() => setInstallersOpen(true)}>
              Who can install
            </Button>
          ) : null}
        </Group>
      </Group>
      {!canInstall && cannotInstall ? (
        <Text size="sm" c="dimmed">
          {cannotInstall}
        </Text>
      ) : null}

      {view.safeMode ? (
        <Alert variant="light" color="yellow" title="Safe mode: no plugin is running">
          {view.safeModeReason ?? 'Studio started without plugins.'} Fix or remove the plugin that failed, then restart
          Studio normally.
        </Alert>
      ) : null}

      <RestartControl restart={view.restart} reasons={restartReasons} canAct={view.canInstall} />

      {attention.length > 0 ? (
        <Text size="sm" className={styles.warning} role="status">
          {attention.length === 1 ? '1 plugin needs attention' : `${attention.length} plugins need attention`}:{' '}
          {attention.map((p) => `${p.info.title} (${(STATUS[p.status] ?? p.status).toLowerCase()})`).join(', ')}.
        </Text>
      ) : null}

      {checkUpdates.data ? (
        <Text size="sm" role="status">
          {checkUpdates.data.length === 0
            ? 'No installed plugin names an update URL to check.'
            : checkUpdates.data.filter((u) => u.availableVersion).length === 0
            ? 'Every plugin with an update URL is up to date.'
            : `${checkUpdates.data.filter((u) => u.availableVersion).length} update(s) available.`}
          {checkUpdates.data
            .filter((u) => u.error)
            .map((u) => ` ${u.id}: could not check (${u.error}).`)
            .join('')}
        </Text>
      ) : null}

      {rows.length === 0 ? (
        <Stack gap="xs" maw={640} py="lg">
          <Text fw={600}>No plugins yet</Text>
          <Text size="sm">
            A plugin is a single <code>.jar</code>. Drop one anywhere on this page, or choose Install plugin; you see
            everything it will be able to do before anything is installed, and most plugins start without a restart.
          </Text>
          <Group gap="md">
            <Anchor href={GUIDE_URL} target="_blank" rel="noopener noreferrer" size="sm">
              Build a plugin →
            </Anchor>
            <Anchor href={TEMPLATE_URL} target="_blank" rel="noopener noreferrer" size="sm">
              Start from the template →
            </Anchor>
          </Group>
        </Stack>
      ) : (
        <VirtualTable
          columns={columns}
          data={rows}
          rowKey={(p) => p.id}
          onRowClick={(p) => setSearch({ plugin: p.id })}
        />
      )}

      <Text size="xs" c="dimmed" className={styles.num}>
        Database connections: {view.budget.inUse} in use of {view.budget.limit} allowed ({view.budget.maxConnections}{' '}
        maximum; each active plugin uses {view.budget.perPlugin}).
      </Text>

      <InstallDialog
        source={source}
        onClose={() => {
          setSource(null);
          if (search.upload) setSearch({ upload: undefined });
        }}
      />
      <InstallersDialog opened={installersOpen} onClose={() => setInstallersOpen(false)} />
      <PluginDrawer
        plugin={open}
        canInstall={canInstall}
        cannotInstall={cannotInstall ?? undefined}
        onClose={() => setSearch({ plugin: undefined })}
        onUpdate={(id) => setSource({ kind: 'update', id })}
      />
    </Stack>
  );
}

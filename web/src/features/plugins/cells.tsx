import { Anchor, Button, Group, Text } from '@mantine/core';

import type { PluginUpdateView, PluginView } from './api.ts';
import { Mark } from './Mark.tsx';
import { UnverifiedBadge } from './UnverifiedBadge.tsx';
import { LicenseBadge } from './LicenseBadge.tsx';
import { LICENSE_LABEL, STATUS, licenseNeedsAction, needsAttention, tone } from './words.ts';
import styles from './Plugins.module.css';

const FIX_LABEL: Record<string, string> = { failed: 'See why', incompatible: 'Update…' };

/** What the fix button says, by what is wrong; blank when nothing is. A license is fixed after the plugin. */
export function fixLabel(plugin: PluginView): string {
  if (needsAttention(plugin)) return FIX_LABEL[plugin.status] ?? 'Details';
  if (licenseNeedsAction(plugin.license)) return plugin.license?.state === 'MISSING' ? 'Add license' : 'License';
  return '';
}

/** The license's state in words, blank for a plugin that needs none. */
export function licenseText(plugin: PluginView): string {
  return plugin.license ? LICENSE_LABEL[plugin.license.state] : '';
}

export function licenseCell(plugin: PluginView) {
  return plugin.license ? <LicenseBadge license={plugin.license} /> : null;
}

/** The plugin's state in words, with the activation step while it is starting. */
export function statusText(plugin: PluginView): string {
  const state = plugin.stuck ? 'Did not stop cleanly' : (STATUS[plugin.status] ?? plugin.status);
  return plugin.status === 'activating' && plugin.progress ? `${state} · ${plugin.progress}` : state;
}

/** The version, with the newer one the last update check found. */
export function versionText(plugin: PluginView, update: PluginUpdateView | undefined): string {
  return update?.availableVersion ? `${plugin.version} ${update.availableVersion} available` : plugin.version;
}

/** The plugin's name, vendor and trust, for the identifying column. */
export function pluginText(plugin: PluginView): string {
  return `${plugin.info.title} ${plugin.info.vendor.name}${plugin.verified ? '' : ' Unverified'}`;
}

export function pluginCell(plugin: PluginView) {
  return (
    <Group gap="xs" wrap="nowrap">
      <Mark plugin={plugin} />
      <Text size="sm" truncate>
        {plugin.info.title}{' '}
        <Text span size="xs" c="dimmed">
          {plugin.info.vendor.name}
        </Text>
      </Text>
      {plugin.verified ? null : <UnverifiedBadge />}
    </Group>
  );
}

export function versionCell(plugin: PluginView, update: PluginUpdateView | undefined, onUpdate: (id: string) => void) {
  return (
    <span className={styles.num}>
      {plugin.version}
      {update?.availableVersion ? (
        <>
          {' '}
          <Anchor component="button" size="xs" onClick={() => onUpdate(plugin.id)}>
            {update.availableVersion} available
          </Anchor>
        </>
      ) : null}
    </span>
  );
}

/** A state that needs someone is emphasised; the word carries the meaning, colour only adds to it. */
export function statusCell(plugin: PluginView) {
  const t = tone(plugin);
  return <span className={t ? styles[t] : undefined}>{statusText(plugin)}</span>;
}

export function fixCell(plugin: PluginView, onOpen: (id: string) => void) {
  return fixLabel(plugin) ? (
    <Button size="compact-xs" variant="default" onClick={() => onOpen(plugin.id)}>
      {fixLabel(plugin)}
    </Button>
  ) : null;
}

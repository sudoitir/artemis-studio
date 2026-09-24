import { Anchor, Button, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { useCan } from '../auth/useCan.ts';
import { useManifest } from '../manifest.ts';
import { bootState } from './boot.ts';

/** Why a plugin's page is not here, in words, by the state the server reports for it. */
function reasonFor(status: string | null | undefined, title: string): string {
  switch (status) {
    case 'disabled':
      return `${title} is installed but disabled.`;
    case 'uninstalled':
      return `${title} was uninstalled. Its data is kept until an administrator purges it.`;
    case 'failed':
      return `${title} failed to start.`;
    case 'incompatible':
      return `${title} does not support this version of ${branding.productShortName}.`;
    case 'needs_restart':
      return `${title} starts when ${branding.productShortName} next restarts.`;
    case 'activating':
      return `${title} is being installed or updated right now.`;
    default:
      return `${title} has no page at this address.`;
  }
}

/**
 * What an address under `/p/<id>/` shows when no plugin page answers it (ADR-0100): whether the
 * plugin is installed at all, and if so the state that keeps its page away — never a blank page
 * or a bare not-found. Administrators are pointed at the plugin's row.
 */
export function PluginUnavailable() {
  const { pluginId, clusterId } = useParams({ strict: false }) as { pluginId?: string; clusterId?: string };
  const manifest = useManifest().data ?? bootState().manifest;
  const { can } = useCan();
  const entry = manifest?.features.find((feature) => feature.origin === 'PLUGIN' && feature.id === pluginId);
  const title = entry?.title ?? pluginId ?? 'This plugin';
  const loadFailure = pluginId ? bootState().failures.get(pluginId) : undefined;

  let heading: string;
  let detail: string | undefined;
  if (manifest?.safeMode) {
    heading = `${branding.productShortName} started in safe mode`;
    detail = 'No plugin is running until an administrator restarts it normally.';
  } else if (!entry) {
    heading = `No plugin called ${pluginId ?? ''} is installed`;
  } else if (entry.status === 'active' && loadFailure) {
    heading = `${title} is running, but its screens could not be shown`;
    detail = `Its ${loadFailure}. The rest of ${branding.productShortName} is unaffected.`;
  } else {
    heading = reasonFor(entry.status, title);
    detail = entry.status === 'active' ? 'The address may be mistyped, or from another version of the plugin.' : undefined;
  }

  return (
    <Stack gap="sm" maw={560}>
      <Title order={2} fz="h3">
        {heading}
      </Title>
      {detail ? <Text size="sm">{detail}</Text> : null}
      {entry && can('user:admin') ? (
        <Anchor component={Link} to="/admin" search={{ tab: 'plugins', plugin: entry.id } as never} size="sm">
          See {title} in Administration → Plugins
        </Anchor>
      ) : null}
      {entry?.status === 'active' && loadFailure ? (
        <Button variant="default" w="fit-content" onClick={() => window.location.reload()}>
          Reload the page
        </Button>
      ) : null}
      <Anchor component={Link} to={clusterId ? `/clusters/${clusterId}` : '/'} size="sm">
        {clusterId ? 'Back to the cluster' : `Back to ${branding.productShortName}`}
      </Anchor>
    </Stack>
  );
}

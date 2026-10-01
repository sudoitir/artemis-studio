import { Button, Code } from '@mantine/core';

import { absoluteLabel } from '../../kernel/time/time.ts';
import type { Column } from '../../ui/table/index.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { PluginInstallerView, TrustedKeyView } from './api.ts';
import styles from './Plugins.module.css';

/** Who may install plugins, and the control that removes them. */
export function installerColumns(deps: {
  /** The last installer cannot be removed. */
  only: boolean;
  removing: string | undefined;
  onRemove: (userId: string) => void;
}): Column<PluginInstallerView>[] {
  return [
    {
      id: 'user',
      header: 'User',
      accessor: (i) => i.username,
      kind: 'identifier',
      priority: 'essential',
      wrap: true,
    },
    { id: 'added', header: 'Added', accessor: (i) => absoluteLabel(i.grantedAt), kind: 'time', priority: 'high' },
    {
      id: 'by',
      header: 'By',
      accessor: (i) => i.grantedBy ?? '—',
      kind: 'identifier',
      priority: 'high',
      wrap: true,
    },
    {
      id: 'remove',
      header: 'Remove',
      accessor: () => 'Remove',
      cell: (i) => (
        <Button
          size="compact-sm"
          variant="subtle"
          disabled={deps.only}
          loading={deps.removing === i.userId}
          onClick={() => deps.onRemove(i.userId)}
          aria-label={`Remove ${i.username}`}
        >
          Remove
        </Button>
      ),
      kind: 'status',
      priority: 'essential',
      min: 12,
    },
  ];
}

/** The keys Studio trusts, with the plugins each signed and the control that removes one. */
/** Why a key pinned by configuration cannot be removed here, and where it can. */
export const CONFIGURED_REASON =
  'Set by configuration. Remove it from artemis-studio.plugins.trusted-keys and restart Studio.';

export function keyColumns(deps: {
  signedPlugins: Readonly<Record<string, string[]>>;
  onRemove: (key: TrustedKeyView) => void;
}): Column<TrustedKeyView>[] {
  return [
    {
      id: 'name',
      header: 'Name',
      accessor: (k) => `${k.name} ${k.subject ?? ''}`,
      cell: (k) => (
        <span className={styles.lines}>
          <span>{k.name}</span>
          {k.source === 'CONFIGURATION' ? <StatusBadge tone="info">From configuration</StatusBadge> : null}
          <span className={styles.note}>{k.subject}</span>
        </span>
      ),
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'fingerprint',
      header: 'Fingerprint',
      accessor: (k) => k.fingerprint,
      cell: (k) => <Code className={styles.fingerprint}>{k.fingerprint}</Code>,
      kind: 'code',
      priority: 'high',
      wrap: true,
    },
    {
      id: 'signed',
      header: 'Signed plugins',
      accessor: (k) => (deps.signedPlugins[k.fingerprint] ?? []).join(', ') || 'None installed',
      kind: 'text',
      priority: 'high',
      wrap: true,
    },
    {
      id: 'added',
      header: 'Added',
      accessor: (k) => `${absoluteLabel(k.addedAt)} by ${k.addedBy}`,
      kind: 'text',
      priority: 'low',
      wrap: true,
    },
    {
      id: 'remove',
      header: 'Remove',
      accessor: () => 'Remove',
      // A configured key cannot be removed here: Remove stays visible and focusable (a disabled button
      // takes no focus), and its description is the reason, shown beside it.
      cell: (k) =>
        k.source === 'CONFIGURATION' ? (
          <span className={styles.lines}>
            <Button
              size="compact-sm"
              variant="subtle"
              aria-label={`Remove ${k.name}`}
              data-disabled
              aria-disabled
              aria-describedby={`configured-${k.fingerprint}`}
            >
              Remove
            </Button>
            <span className={styles.note} id={`configured-${k.fingerprint}`}>
              {CONFIGURED_REASON}
            </span>
          </span>
        ) : (
          <Button size="compact-sm" variant="subtle" aria-label={`Remove ${k.name}`} onClick={() => deps.onRemove(k)}>
            Remove
          </Button>
        ),
      kind: 'status',
      priority: 'essential',
      min: 12,
    },
  ];
}

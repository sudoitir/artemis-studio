import { Anchor } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useManifest } from '../../kernel/manifest.ts';
import styles from './Plugins.module.css';

const ATTENTION = new Set(['failed', 'incompatible', 'needs_restart']);

/**
 * In the header, for administrators only: safe mode, or plugins that need someone. Read from the
 * manifest the page already has, so it costs nothing and never polls; nothing shows while all is well.
 */
export function HeaderIndicator() {
  const { can } = useCan();
  const manifest = useManifest().data;
  if (!manifest || !can('user:admin')) return null;
  const attention = manifest.features.filter((f) => f.origin === 'PLUGIN' && ATTENTION.has(f.status ?? ''));
  if (!manifest.safeMode && attention.length === 0) return null;
  const text = manifest.safeMode
    ? 'Safe mode: plugins stopped'
    : attention.length === 1
      ? `Plugin ${attention[0].title} needs attention`
      : `${attention.length} plugins need attention`;
  return (
    <Anchor component={Link} to="/admin" search={{ tab: 'plugins' } as never} size="sm" className={styles.warning}>
      {text}
    </Anchor>
  );
}

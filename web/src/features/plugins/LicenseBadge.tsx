import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { PluginLicenseView } from './api.ts';
import { LICENSE_LABEL } from './words.ts';

const DANGER = new Set(['EXPIRED', 'OVER_LIMIT', 'INVALID']);
const WARNING = new Set(['MISSING', 'UNCHECKED', 'EXPIRING']);

/** A license's state on a plugin's row. Words carry the meaning; the tone is redundant emphasis. */
export function LicenseBadge({ license }: Readonly<{ license: PluginLicenseView }>) {
  let tone: 'neutral' | 'warning' | 'danger' = 'neutral';
  if (DANGER.has(license.state)) tone = 'danger';
  else if (WARNING.has(license.state)) tone = 'warning';
  return <StatusBadge tone={tone}>{LICENSE_LABEL[license.state]}</StatusBadge>;
}

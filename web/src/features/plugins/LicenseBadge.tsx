import { Badge } from '@mantine/core';

import type { PluginLicenseView } from './api.ts';
import { LICENSE_LABEL } from './words.ts';

const DANGER = new Set(['EXPIRED', 'OVER_LIMIT', 'INVALID']);
const WARNING = new Set(['MISSING', 'UNCHECKED', 'EXPIRING']);

/** A license's state on a plugin's row. Words carry the meaning; the colour is redundant emphasis. */
export function LicenseBadge({ license }: Readonly<{ license: PluginLicenseView }>) {
  const danger = DANGER.has(license.state);
  const warning = WARNING.has(license.state);
  let colour: string | undefined;
  if (danger) colour = 'var(--as-danger)';
  else if (warning) colour = 'var(--as-warning)';
  let tone = 'gray';
  if (danger) tone = 'red';
  else if (warning) tone = 'yellow';
  return (
    <Badge size="xs" variant={danger || warning ? 'light' : 'outline'} color={tone} c={colour}>
      {LICENSE_LABEL[license.state]}
    </Badge>
  );
}

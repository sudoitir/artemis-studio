import { ActionIcon, Tooltip } from '@mantine/core';
import { IconDeviceDesktop, IconMoon, IconSun } from '@tabler/icons-react';

import { useColorSchemeToggle } from './useColorSchemeToggle.ts';

const ICONS = { auto: IconDeviceDesktop, light: IconSun, dark: IconMoon };

/**
 * Following the system, light or dark, from the header: one control that cycles the three. Mantine's own
 * manager remembers the choice in this browser, and a first visit follows the system (`main.tsx`).
 *
 * The icon shows the scheme in use; the name says where the control goes, not where it is, so an operator
 * reading "Use light theme (system is dark)" knows what activating it will do.
 */
export function ColorSchemeToggle() {
  const { scheme, toggle, label } = useColorSchemeToggle();
  const Icon = ICONS[scheme];
  return (
    <Tooltip label={label}>
      <ActionIcon variant="subtle" color="graphite" aria-label={label} onClick={toggle}>
        <Icon size={18} aria-hidden />
      </ActionIcon>
    </Tooltip>
  );
}

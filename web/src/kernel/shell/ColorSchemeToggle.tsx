import { ActionIcon, Tooltip } from '@mantine/core';
import { IconMoon, IconSun } from '@tabler/icons-react';

import { useColorSchemeToggle } from './useColorSchemeToggle.ts';

/**
 * Light or dark, from the header. Mantine's own manager remembers the choice in this
 * browser; dark stays the first-visit default (`main.tsx`).
 *
 * The name says where the control goes, not where it is: an operator reading
 * "Switch to light theme" knows what activating it will do.
 */
export function ColorSchemeToggle() {
  const { toggle, label } = useColorSchemeToggle();
  return (
    <Tooltip label={label}>
      <ActionIcon variant="subtle" color="gray" aria-label={label} onClick={toggle}>
        {label === 'Switch to light theme' ? (
          <IconSun size={18} aria-hidden />
        ) : (
          <IconMoon size={18} aria-hidden />
        )}
      </ActionIcon>
    </Tooltip>
  );
}

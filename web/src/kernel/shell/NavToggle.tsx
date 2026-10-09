import { ActionIcon, Tooltip } from '@mantine/core';
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from '@tabler/icons-react';

import { modShortcut } from '../keyboard/keys.ts';

/**
 * A real button — `aria-expanded`/`aria-controls` so the collapse is announced. While the window is
 * too narrow for the full sidebar (`forced`) it says why it does nothing: it is `aria-disabled`, not
 * `disabled`, so it keeps its focus and its tooltip, and the reason stays reachable by keyboard.
 * `onToggle` is a no-op while forced (`useNavCollapsed`).
 */
export function NavToggle({
  collapsed,
  forced = false,
  onToggle,
  controls,
}: Readonly<{
  collapsed: boolean;
  /** The window is narrower than the sidebar's threshold, so the rail is not the viewer's choice. */
  forced?: boolean;
  onToggle: () => void;
  controls: string;
}>) {
  const action = collapsed ? 'Expand sidebar' : 'Collapse sidebar';
  const label = forced ? 'Sidebar stays collapsed in a narrow window' : action;
  return (
    <Tooltip label={forced ? label : `${label} (${modShortcut('B')})`} position="right">
      <ActionIcon
        variant="subtle"
        color="graphite"
        aria-expanded={!collapsed}
        aria-controls={controls}
        aria-label={label}
        aria-disabled={forced || undefined}
        data-disabled={forced || undefined}
        onClick={onToggle}
      >
        {collapsed ? (
          <IconLayoutSidebarLeftExpand size={18} stroke={1.5} aria-hidden />
        ) : (
          <IconLayoutSidebarLeftCollapse size={18} stroke={1.5} aria-hidden />
        )}
      </ActionIcon>
    </Tooltip>
  );
}

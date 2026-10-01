import { ActionIcon, Tooltip } from '@mantine/core';
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from '@tabler/icons-react';

/**
 * A real button — `aria-expanded`/`aria-controls` so the collapse is announced. While the window is
 * too narrow for the full sidebar (`forced`) it stays visible and says why it does nothing.
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
    <Tooltip label={forced ? label : `${label} (⌘B)`} position="right">
      <ActionIcon
        variant="default"
        aria-expanded={!collapsed}
        aria-controls={controls}
        aria-label={label}
        data-disabled={forced || undefined}
        disabled={forced}
        onClick={onToggle}
      >
        {collapsed ? <IconLayoutSidebarLeftExpand size={18} /> : <IconLayoutSidebarLeftCollapse size={18} />}
      </ActionIcon>
    </Tooltip>
  );
}

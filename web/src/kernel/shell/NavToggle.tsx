import { ActionIcon, Tooltip } from '@mantine/core';
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from '@tabler/icons-react';

/** A real button — `aria-expanded`/`aria-controls` so the collapse is announced. */
export function NavToggle({
  collapsed,
  onToggle,
  controls,
}: {
  collapsed: boolean;
  onToggle: () => void;
  controls: string;
}) {
  return (
    <Tooltip label={collapsed ? 'Expand sidebar (⌘B)' : 'Collapse sidebar (⌘B)'} position="right">
      <ActionIcon
        variant="subtle"
        color="gray"
        aria-expanded={!collapsed}
        aria-controls={controls}
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        onClick={onToggle}
      >
        {collapsed ? <IconLayoutSidebarLeftExpand size={18} /> : <IconLayoutSidebarLeftCollapse size={18} />}
      </ActionIcon>
    </Tooltip>
  );
}

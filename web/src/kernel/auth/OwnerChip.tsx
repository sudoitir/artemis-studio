import { Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import badge from '../../ui/StatusBadge.module.css';
import link from '../../ui/InlineLink.module.css';
import { useCan } from './useCan.ts';

/**
 * The team that owns a queue or address, as a link to the team when the caller may open it (an installation
 * administrator, or that team's own admin); otherwise the name alone, because the team page would answer not found.
 * Or that no team owns it.
 */
export function OwnerChip({ team }: Readonly<{ team?: { id: string; name: string } | null }>) {
  const { can, teams } = useCan();
  if (!team) {
    return (
      <Text span size="xs" c="dimmed">
        No owner
      </Text>
    );
  }
  const opens = can('user:admin') || teams.some((t) => t.teamId === team.id && t.teamAdmin);
  return (
    <span className={badge.badge} data-tone="neutral">
      {opens ? (
        <Link
          to="/admin"
          search={{ tab: 'teams', team: team.id }}
          className={link.link}
          aria-label={`Owner: team ${team.name}`}
        >
          {team.name}
        </Link>
      ) : (
        team.name
      )}
    </span>
  );
}

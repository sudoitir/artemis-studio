import { Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import badge from '../../ui/StatusBadge.module.css';
import link from '../../ui/InlineLink.module.css';

/** The team that owns a queue or address, as a link to the team; or that no team does. */
export function OwnerChip({ team }: Readonly<{ team?: { id: string; name: string } | null }>) {
  if (!team) {
    return (
      <Text span size="xs" c="dimmed">
        No owner
      </Text>
    );
  }
  return (
    <span className={badge.badge} data-tone="neutral">
      <Link
        to="/admin"
        search={{ tab: 'teams', team: team.id }}
        className={link.link}
        aria-label={`Owner: team ${team.name}`}
      >
        {team.name}
      </Link>
    </span>
  );
}

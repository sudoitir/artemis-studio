import { useState } from 'react';
import { ActionIcon, Group, Stack, Tabs } from '@mantine/core';
import { IconArrowLeft, IconPencil, IconTrash } from '@tabler/icons-react';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';

import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { useTeam, type PatternKind, type TeamView } from './api.ts';
import classes from './Security.module.css';
import { teamSearch } from './teamColumns.tsx';
import { DeleteTeam, TeamNameDialog } from './teamDialogs.tsx';
import { useTeamAccess } from './teamAccess.ts';
import { TeamMembers } from './TeamMembers.tsx';
import { TeamPatterns, type PatternDraft } from './TeamPatterns.tsx';
import { TeamShares } from './TeamShares.tsx';
import { TeamUnowned } from './TeamUnowned.tsx';
import { countOf } from './teamWords.ts';

const TABS = [
  { id: 'patterns', title: 'Patterns', count: (t: TeamView) => t.patterns.length },
  { id: 'members', title: 'Members', count: (t: TeamView) => t.members.length },
  { id: 'shares', title: 'Shares', count: (t: TeamView) => t.sharesOut.length + t.sharesIn.length },
  { id: 'unowned', title: 'Unowned', count: undefined },
] as const;

type TabId = (typeof TABS)[number]['id'];

const isTab = (value: unknown): value is TabId => TABS.some((t) => t.id === value);

/** What a team owns and who uses it, in a line: the first thing to know about a team. */
function teamSummary(team: TeamView): string {
  const clusters = new Set(team.patterns.map((p) => p.clusterId)).size;
  const owns =
    team.patterns.length === 0
      ? 'Owns nothing yet: add a pattern to give it queues and addresses'
      : `Owns ${countOf(team.patterns.length, 'pattern')} on ${countOf(clusters, 'cluster')}`;
  return `${owns}. ${countOf(team.members.length, 'member')}; shares ${team.sharesOut.length} out and ${team.sharesIn.length} in.`;
}

/** The way back to the list, as a real link: it opens in a new tab like any other. */
function AllTeams() {
  return (
    <Link to="." search={teamSearch(undefined) as never} className={`${linkClasses.link} ${classes.back}`}>
      <IconArrowLeft size="1rem" aria-hidden />
      All teams
    </Link>
  );
}

/**
 * One team: its patterns, members and shares, and the resources on a cluster that no team owns. `focusNewPattern`
 * is set when the team was just created, so its first pattern is where focus lands; `onFocused` clears it.
 */
export function TeamPage({
  teamId,
  focusNewPattern,
  onFocused,
  onDeleted,
}: Readonly<{ teamId: string; focusNewPattern: boolean; onFocused: () => void; onDeleted: () => void }>) {
  const team = useTeam(teamId);
  const { userAdmin, verdict } = useTeamAccess();
  const search = useSearch({ strict: false }) as { teamTab?: string };
  const navigate = useNavigate();
  const tab: TabId = isTab(search.teamTab) ? search.teamTab : 'patterns';
  const setTab = (next: string | null) =>
    navigate({
      to: '.',
      search: (prev: Record<string, unknown>) => ({ ...prev, teamTab: next && next !== 'patterns' ? next : undefined }),
    });
  // A name chosen in the Unowned tab, carried to the pattern form it pre-fills.
  const [draft, setDraft] = useState<PatternDraft | undefined>();
  const [renaming, setRenaming] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  if (team.isPending) {
    return (
      <Stack gap="xs">
        <AllTeams />
        <LoadingState label="Loading team" blockSize="16rem" />
      </Stack>
    );
  }
  if (team.isError) {
    return (
      <Stack gap="xs">
        <AllTeams />
        <ErrorState error={team.error} onRetry={() => void team.refetch()} />
      </Stack>
    );
  }

  const assign = (clusterId: string, kind: PatternKind, name: string) => {
    setDraft({ clusterId, kind, pattern: name, nonce: Date.now() });
    void setTab('patterns');
  };

  const actions = (
    <Group gap="xs">
      <CapabilityGate verdict={verdict('Renaming a team')} what="renaming the team">
        <ActionIcon
          variant="default"
          size="lg"
          disabled={!userAdmin}
          onClick={() => setRenaming(true)}
          aria-label={`Rename ${team.data.name}`}
        >
          <IconPencil size="1rem" aria-hidden />
        </ActionIcon>
      </CapabilityGate>
      <CapabilityGate verdict={verdict('Deleting a team')} what="deleting the team">
        <ActionIcon
          variant="default"
          size="lg"
          disabled={!userAdmin}
          onClick={() => setDeleteOpen(true)}
          aria-label={`Delete ${team.data.name}`}
        >
          <IconTrash size="1rem" aria-hidden />
        </ActionIcon>
      </CapabilityGate>
    </Group>
  );

  return (
    <Stack gap="xs">
      <AllTeams />
      <Section title={team.data.name} description={teamSummary(team.data)} actions={actions}>
        <Tabs value={tab} onChange={setTab}>
          <Tabs.List aria-label={`${team.data.name} sections`}>
            {TABS.map(({ id, title, count }) => (
              <Tabs.Tab key={id} value={id}>
                {count ? `${title} (${count(team.data)})` : title}
              </Tabs.Tab>
            ))}
          </Tabs.List>
          <Tabs.Panel value="patterns" pt="md">
            <TeamPatterns team={team.data} draft={draft} focusFirst={focusNewPattern} onFocused={onFocused} />
          </Tabs.Panel>
          <Tabs.Panel value="members" pt="md">
            <TeamMembers team={team.data} />
          </Tabs.Panel>
          <Tabs.Panel value="shares" pt="md">
            <TeamShares team={team.data} />
          </Tabs.Panel>
          <Tabs.Panel value="unowned" pt="md">
            <TeamUnowned onAssign={assign} />
          </Tabs.Panel>
        </Tabs>
      </Section>

      <TeamNameDialog
        naming={renaming ? team.data : null}
        onClose={() => setRenaming(false)}
        onDone={() => setRenaming(false)}
      />
      <DeleteTeam team={team.data} opened={deleteOpen} onClose={() => setDeleteOpen(false)} onDeleted={onDeleted} />
    </Stack>
  );
}

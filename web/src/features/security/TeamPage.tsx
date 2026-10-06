import { useState } from 'react';
import { Button, Tabs } from '@mantine/core';
import { IconArrowLeft } from '@tabler/icons-react';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { useTeam, type PatternKind } from './api.ts';
import { TeamMembers } from './TeamMembers.tsx';
import { TeamPatterns, type PatternDraft } from './TeamPatterns.tsx';
import { TeamShares } from './TeamShares.tsx';
import { TeamUnowned } from './TeamUnowned.tsx';

const TABS = [
  { id: 'patterns', title: 'Patterns' },
  { id: 'members', title: 'Members' },
  { id: 'shares', title: 'Shares' },
  { id: 'unowned', title: 'Unowned' },
] as const;

type TabId = (typeof TABS)[number]['id'];

const isTab = (value: unknown): value is TabId => TABS.some((t) => t.id === value);

/** One team: its patterns, members and shares, and the resources on a cluster that no team owns. */
export function TeamPage({ teamId, onBack }: Readonly<{ teamId: string; onBack: () => void }>) {
  const team = useTeam(teamId);
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

  const back = (
    <Button variant="subtle" size="compact-sm" leftSection={<IconArrowLeft size="1rem" aria-hidden />} onClick={onBack}>
      All teams
    </Button>
  );

  if (team.isPending) return <LoadingState label="Loading team" blockSize="16rem" />;
  if (team.isError) {
    return <ErrorState error={team.error} onRetry={() => void team.refetch()} actions={back} />;
  }

  const assign = (clusterId: string, kind: PatternKind, name: string) => {
    setDraft({ clusterId, kind, pattern: name, nonce: Date.now() });
    void setTab('patterns');
  };

  return (
    <Section title={team.data.name} description="Patterns, members and shares of this team." actions={back}>
      <Tabs value={tab} onChange={setTab}>
        <Tabs.List aria-label={`${team.data.name} sections`}>
          {TABS.map(({ id, title }) => (
            <Tabs.Tab key={id} value={id}>
              {title}
            </Tabs.Tab>
          ))}
        </Tabs.List>
        <Tabs.Panel value="patterns" pt="md">
          <TeamPatterns team={team.data} draft={draft} />
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
  );
}

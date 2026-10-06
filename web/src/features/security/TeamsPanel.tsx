import { useState } from 'react';
import { Button, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useNavigate, useSearch } from '@tanstack/react-router';

import type { ApiError } from '../../kernel/api/request.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useClusters } from '../clusters/index.ts';
import { useCreateTeam, useDeleteTeam, useRenameTeam, useTeams, type TeamSummary } from './api.ts';
import { withNotice } from './outcomes.ts';
import { teamColumns } from './teamColumns.tsx';
import { useTeamAccess } from './teamAccess.ts';
import { TeamPage } from './TeamPage.tsx';
import { countOf, problemSlug } from './teamWords.ts';

const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };
const RENAME: ActionVerb = { verb: 'Rename', past: 'Renamed', progressive: 'Renaming' };
const DELETE: ActionVerb = { verb: 'Delete', past: 'Deleted', progressive: 'Deleting' };

const NAME_ERROR = 'Name the team after the group of people who own these queues.';

const rowKey = (t: TeamSummary) => t.id;

/**
 * Teams (team-access spec): who owns which queue and address name patterns, and who may act on them.
 * The open team is in the address (`?team=`), so a team's page can be shared.
 */
export function TeamsPanel() {
  const search = useSearch({ strict: false }) as { team?: string; teamTab?: string };
  const navigate = useNavigate();
  const openTeam = (team?: string) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, team, teamTab: undefined }) });

  return search.team ? (
    <TeamPage key={search.team} teamId={search.team} onBack={() => void openTeam()} />
  ) : (
    <TeamList onOpen={(id) => void openTeam(id)} />
  );
}

function TeamList({ onOpen }: Readonly<{ onOpen: (teamId: string) => void }>) {
  const teams = useTeams();
  const { userAdmin, loading: accessLoading, verdict } = useTeamAccess();
  const [naming, setNaming] = useState<TeamSummary | 'new' | null>(null);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [deleting, setDeleting] = useState<TeamSummary | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const clusters = useClusters();
  const columns = teamColumns({
    clusterName: (id) => clusters.data?.find((c) => c.id === id)?.name ?? 'Unknown cluster',
    editable: userAdmin,
    onOpen: (t) => onOpen(t.id),
    onRename: setNaming,
    onDelete: (t) => {
      setDeleting(t);
      setDeleteOpen(true);
    },
  });
  const count = teams.data?.length;
  const title = 'Teams';
  const description =
    'A team owns queue and address name patterns on clusters. Its members see and operate only what those patterns cover.';

  // Until access is known the caller counts as an admin, so a non-admin's notice would appear late and push the
  // table down: wait for it, so the notice and the table arrive together.
  if (accessLoading) {
    return (
      <Section title={title} description={description}>
        <LoadingState label="Loading teams" />
      </Section>
    );
  }

  return (
    <Section title={title} description={description}>
      {userAdmin ? null : (
        <Notice title="Team admin">
          You see the teams you administer. Creating, renaming and deleting teams, and changing a team&apos;s patterns
          and shares, need the user:admin permission. You can change who is a member.
        </Notice>
      )}
      <DataTable
        variant="static"
        label="Teams"
        storageKey="security.teams"
        columns={columns}
        data={teams.data ?? []}
        rowKey={rowKey}
        loading={teams.isPending}
        error={teams.isError ? <ErrorState error={teams.error} onRetry={() => void teams.refetch()} /> : undefined}
        toolbar={{
          start: (
            <CapabilityGate verdict={verdict('Creating a team')} what="creating a team">
              <Button disabled={!userAdmin} onClick={() => setNaming('new')}>
                New team
              </Button>
            </CapabilityGate>
          ),
          end:
            count === undefined ? undefined : (
              <Text size="sm" c="dimmed">
                {count} team{count === 1 ? '' : 's'}
              </Text>
            ),
        }}
        empty={
          <EmptyState
            kind="empty"
            title="No teams"
            description={
              userAdmin
                ? 'A team owns queue and address name patterns on clusters, and its members see and operate only what those patterns cover. Create one to share a cluster between groups of people.'
                : 'You do not administer a team yet. Ask a user administrator to make you a team admin of one.'
            }
          />
        }
      />

      <Modal
        opened={naming !== null}
        onClose={() => setNaming(null)}
        title={naming === 'new' ? 'New team' : `Rename ${naming?.name ?? ''}`}
      >
        {naming === null ? null : (
          <TeamNameForm key={naming === 'new' ? 'new' : naming.id} team={naming} onDone={() => setNaming(null)} />
        )}
      </Modal>
      <DeleteTeam team={deleting} opened={deleteOpen} onClose={() => setDeleteOpen(false)} />
    </Section>
  );
}

/** The name of a new or renamed team. A name already taken is answered beside the field. */
function TeamNameForm({ team, onDone }: Readonly<{ team: TeamSummary | 'new'; onDone: () => void }>) {
  const create = useCreateTeam();
  const rename = useRenameTeam();
  const form = useForm({
    initialValues: { name: team === 'new' ? '' : team.name },
    validateInputOnBlur: true,
    validate: { name: (v) => (v.trim() ? null : NAME_ERROR) },
  });
  const pending = create.isPending || rename.isPending;

  const submit = form.onSubmit(({ name }) => {
    const subject = `team ${name.trim()}`;
    const onError = (action: ActionVerb, next: string) => (error: ApiError) => {
      if (problemSlug(error) === 'duplicate-team-name') {
        form.setErrors({ name: 'A team with that name already exists. Choose another name.' });
        form.getInputNode('name')?.focus();
      } else {
        notify.failed({ action, subject, cause: error.message, next });
      }
    };
    if (team === 'new') {
      create.mutate(name.trim(), {
        onSuccess: () => {
          notify.succeeded({ action: CREATE, subject });
          onDone();
        },
        onError: onError(CREATE, 'No team was created. Try again.'),
      });
    } else {
      rename.mutate(
        { teamId: team.id, name: name.trim() },
        {
          onSuccess: () => {
            notify.succeeded({ action: RENAME, subject });
            onDone();
          },
          onError: onError(RENAME, 'The team keeps its name. Try again.'),
        },
      );
    }
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap="sm">
        <TextInput label="Name" {...form.getInputProps('name')} required />
        <Button type="submit" loading={pending}>
          {team === 'new' ? 'Create team' : 'Rename team'}
        </Button>
      </Stack>
    </form>
  );
}

/** States what deleting a team ends, from its own counts, before it can be armed, then asks for its name. */
function DeleteTeam({
  team,
  opened,
  onClose,
}: Readonly<{ team: TeamSummary | null; opened: boolean; onClose: () => void }>) {
  const remove = useDeleteTeam();
  const confirm = (t: TeamSummary) =>
    remove.mutate(t.id, withNotice(DELETE, `team ${t.name}`, 'The team still exists. Try again.', onClose));

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={team ? `Delete ${team.name}` : 'Delete team'}
      tone="danger"
      typedName={team?.name}
      pending={remove.isPending}
      confirmLabel="Delete team"
      consequence={
        team
          ? `This removes the team's ${countOf(team.patterns.length, 'pattern')}, ${countOf(team.memberCount, 'member')} and ${countOf(team.sharesOut + team.sharesIn, 'share')}. Its members lose the access the team gave them on their next request. The queues and addresses themselves are not touched.`
          : ''
      }
      onConfirm={() => team && confirm(team)}
    />
  );
}

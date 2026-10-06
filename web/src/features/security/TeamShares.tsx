import { useState } from 'react';
import { Button, Fieldset, SegmentedControl, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid, serverFieldErrors } from '../../ui/formErrors.ts';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useClusters } from '../clusters/index.ts';
import {
  useAddShare,
  useRemoveShare,
  useRoles,
  useTeams,
  type PatternKind,
  type ShareView,
  type TeamView,
} from './api.ts';
import { withNotice } from './outcomes.ts';
import classes from './Security.module.css';
import { useTeamAccess } from './teamAccess.ts';
import { shareColumns } from './teamColumns.tsx';
import { KIND_OPTIONS, patternFault, teamProblem } from './teamWords.ts';

const ADD: ActionVerb = { verb: 'Share', past: 'Shared', progressive: 'Sharing' };
const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };

const rowKey = (s: ShareView) => s.id;

/**
 * What this team shares with other teams, and what is shared with it. A share gives the receiving team a team
 * role on part of what this team owns; one whose pattern the owner no longer covers grants nothing.
 */
export function TeamShares({ team }: Readonly<{ team: TeamView }>) {
  const clusters = useClusters();
  const { userAdmin } = useTeamAccess();
  const [removing, setRemoving] = useState<ShareView | null>(null);
  const [removeOpen, setRemoveOpen] = useState(false);

  const clusterName = (id: string) => clusters.data?.find((c) => c.id === id)?.name ?? 'Unknown cluster';
  const out = shareColumns({
    clusterName,
    direction: 'out',
    editable: userAdmin,
    onRemove: (s) => {
      setRemoving(s);
      setRemoveOpen(true);
    },
  });
  const incoming = shareColumns({ clusterName, direction: 'in', editable: false, onRemove: () => {} });
  const uncovered = team.sharesOut.filter((s) => !s.covered);

  return (
    <Stack gap="lg">
      <Section
        title="Shared with other teams"
        headingLevel={3}
        description="Parts of what this team owns that other teams may use, at a team role."
      >
        {uncovered.length === 0 ? null : (
          <Notice title="Not covered" tone="warning">
            {uncovered.length === 1 ? 'One share grants' : `${uncovered.length} shares grant`} nothing because this
            team&apos;s patterns no longer contain {uncovered.length === 1 ? 'its' : 'their'} pattern. Add the pattern
            back, or remove the share.
          </Notice>
        )}
        <DataTable
          variant="static"
          label={`Shares of ${team.name} with other teams`}
          storageKey="security.team-shares-out"
          columns={out}
          data={team.sharesOut}
          rowKey={rowKey}
          empty={
            <EmptyState
              kind="empty"
              title="Nothing shared"
              description="A share lets another team see or operate part of what this team owns, without owning it."
            />
          }
        />
      </Section>

      <Section title="Share a pattern" headingLevel={3}>
        {userAdmin ? null : (
          <Text size="sm" className={classes.reason}>
            Changing a team&apos;s shares needs the user:admin permission. Ask a user administrator.
          </Text>
        )}
        <Fieldset legend="New share" disabled={!userAdmin}>
          <AddShare team={team} />
        </Fieldset>
      </Section>

      <Section
        title="Shared with this team"
        headingLevel={3}
        description="What other teams have shared with this one. Only the owning team can change a share."
      >
        <DataTable
          variant="static"
          label={`Shares with ${team.name} from other teams`}
          storageKey="security.team-shares-in"
          columns={incoming}
          data={team.sharesIn}
          rowKey={rowKey}
          empty={
            <EmptyState
              kind="empty"
              title="Nothing shared with this team"
              description="No other team has shared part of what it owns with this team."
            />
          }
        />
      </Section>

      <RemoveShare
        team={team}
        share={removing}
        opened={removeOpen}
        onClose={() => setRemoveOpen(false)}
        clusterName={clusterName}
      />
    </Stack>
  );
}

function AddShare({ team }: Readonly<{ team: TeamView }>) {
  const clusters = useClusters();
  const teams = useTeams();
  const roles = useRoles();
  const add = useAddShare(team.id);
  const form = useForm<{
    targetTeamId: string | null;
    clusterId: string | null;
    kind: PatternKind;
    pattern: string;
    roleId: string | null;
  }>({
    initialValues: { targetTeamId: null, clusterId: null, kind: 'QUEUE', pattern: '', roleId: null },
    validateInputOnBlur: true,
    validate: {
      targetTeamId: (v) => (v ? null : 'Choose the team to share with.'),
      clusterId: (v) => (v ? null : 'Choose the cluster the pattern is on.'),
      pattern: patternFault,
      roleId: (v) => (v ? null : 'Choose the team role the receiving team holds.'),
    },
  });
  const teamOptions = (teams.data ?? []).filter((t) => t.id !== team.id).map((t) => ({ value: t.id, label: t.name }));
  const roleOptions = (roles.data ?? []).filter((r) => r.teamAssignable).map((r) => ({ value: r.id, label: r.name }));

  const submit = form.onSubmit(({ targetTeamId, clusterId, kind, pattern, roleId }) => {
    if (!targetTeamId || !clusterId || !roleId) return;
    const target = teamOptions.find((t) => t.value === targetTeamId)?.label ?? 'the team';
    const subject = `${pattern} with ${target}`;
    const pendingId = notify.pending({ action: ADD, subject });
    add.mutate(
      { targetTeamId, clusterId, kind, pattern, roleId },
      {
        onSuccess: () => {
          notify.succeeded({ action: ADD, subject, pendingId });
          form.reset();
        },
        onError: (error) => {
          const reason = teamProblem(error) ?? error.message;
          const fields = serverFieldErrors(error, ['targetTeamId', 'clusterId', 'kind', 'pattern', 'roleId']);
          if (/outside-owner|duplicate-team-share/.test(error.type)) fields.pattern = reason;
          if (/share-with-self/.test(error.type)) fields.targetTeamId = reason;
          if (/not-a-team-role/.test(error.type)) fields.roleId = reason;
          if (Object.keys(fields).length > 0) form.setErrors(fields);
          notify.failed({
            action: ADD,
            subject,
            pendingId,
            cause: reason,
            next: 'Nothing was shared. Fix the share and try again.',
          });
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap="sm">
        <FieldRow>
          <Select
            label="Share with"
            data={teamOptions}
            searchable
            nothingFoundMessage="No other teams"
            {...form.getInputProps('targetTeamId')}
            required
          />
          <Select
            label="As team role"
            data={roleOptions}
            nothingFoundMessage="No team roles"
            {...form.getInputProps('roleId')}
            required
          />
        </FieldRow>
        <FieldRow>
          <Select
            label="Cluster"
            data={(clusters.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
            searchable
            nothingFoundMessage="No clusters"
            {...form.getInputProps('clusterId')}
            required
          />
          <TextInput
            label="Pattern"
            description="Must lie inside this team's own patterns."
            {...form.getInputProps('pattern')}
            required
          />
        </FieldRow>
        <SegmentedControl
          aria-label="Kind"
          data={KIND_OPTIONS}
          value={form.values.kind}
          onChange={(next) => form.setFieldValue('kind', next as PatternKind)}
        />
        <div>
          <Button type="submit" loading={add.isPending}>
            Share pattern
          </Button>
        </div>
      </Stack>
    </form>
  );
}

/** States what removing a share ends for the receiving team, then asks for the pattern. */
function RemoveShare({
  team,
  share,
  opened,
  onClose,
  clusterName,
}: Readonly<{
  team: TeamView;
  share: ShareView | null;
  opened: boolean;
  onClose: () => void;
  clusterName: (clusterId: string) => string;
}>) {
  const remove = useRemoveShare(team.id);
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={share ? `Stop sharing ${share.pattern} with ${share.targetTeamName}` : 'Remove share'}
      tone="danger"
      typedName={share?.pattern}
      pending={remove.isPending}
      confirmLabel="Remove share"
      consequence={
        share
          ? `Members of ${share.targetTeamName} lose the ${share.roleName} access to ${share.pattern} on ${clusterName(share.clusterId)} on their next request, unless another grant still gives it. ${share.covered ? '' : "The share is not covered by this team's patterns, so it grants nothing today. "}You can share it again.`
          : ''
      }
      onConfirm={() =>
        share &&
        remove.mutate(
          share.id,
          withNotice(
            REMOVE,
            `the share of ${share.pattern} with ${share.targetTeamName}`,
            'The share still applies. Try again.',
            onClose,
          ),
        )
      }
    />
  );
}

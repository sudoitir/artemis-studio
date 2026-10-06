import { ActionIcon, Button, Select } from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import type { MemberView, PatternView, ShareView, TeamSummary, UnownedView } from './api.ts';
import classes from './Security.module.css';
import { KIND_WORDS } from './teamWords.ts';

/** What the teams table needs from its panel. `editable` is whether the caller may rename and delete teams. */
export interface TeamRows {
  editable: boolean;
  onOpen: (team: TeamSummary) => void;
  onRename: (team: TeamSummary) => void;
  onDelete: (team: TeamSummary) => void;
}

/** The teams: the name opens the team and is never hidden, nor are the actions; the counts go last when narrow. */
export function teamColumns({ editable, onOpen, onRename, onDelete }: TeamRows): Column<TeamSummary>[] {
  return [
    {
      id: 'name',
      header: 'Team',
      accessor: (t) => t.name,
      cell: (t) => (
        <Button variant="subtle" size="compact-sm" onClick={() => onOpen(t)}>
          {t.name}
        </Button>
      ),
      kind: 'identifier',
      priority: 'essential',
    },
    { id: 'members', header: 'Members', accessor: (t) => t.memberCount, kind: 'number', priority: 'high' },
    { id: 'patterns', header: 'Patterns', accessor: (t) => t.patternCount, kind: 'number', priority: 'high' },
    { id: 'sharesOut', header: 'Shared out', accessor: (t) => t.sharesOut, kind: 'number', priority: 'low' },
    { id: 'sharesIn', header: 'Shared in', accessor: (t) => t.sharesIn, kind: 'number', priority: 'low' },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Rename Delete',
      cell: (t) => (
        <span className={classes.controls}>
          <ActionIcon variant="subtle" disabled={!editable} onClick={() => onRename(t)} aria-label={`Rename ${t.name}`}>
            <IconPencil size="1rem" aria-hidden />
          </ActionIcon>
          <ActionIcon variant="subtle" disabled={!editable} onClick={() => onDelete(t)} aria-label={`Delete ${t.name}`}>
            <IconTrash size="1rem" aria-hidden />
          </ActionIcon>
        </span>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

export interface PatternRows {
  clusterName: (clusterId: string) => string;
  editable: boolean;
  onRemove: (pattern: PatternView) => void;
}

/** A team's patterns: where, which kind and what they match; the removal is never hidden. */
export function patternColumns({ clusterName, editable, onRemove }: PatternRows): Column<PatternView>[] {
  return [
    {
      id: 'cluster',
      header: 'Cluster',
      accessor: (p) => clusterName(p.clusterId),
      kind: 'text',
      priority: 'essential',
    },
    { id: 'kind', header: 'Kind', accessor: (p) => KIND_WORDS[p.kind], kind: 'text', priority: 'high' },
    { id: 'pattern', header: 'Pattern', accessor: (p) => p.pattern, kind: 'code', priority: 'essential' },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Remove',
      cell: (p) => (
        <ActionIcon
          variant="subtle"
          disabled={!editable}
          onClick={() => onRemove(p)}
          aria-label={`Remove pattern ${p.pattern}`}
        >
          <IconTrash size="1rem" aria-hidden />
        </ActionIcon>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

/** Who a member is: a user's name, or a directory group with the provider that sends it. */
export const memberName = (m: MemberView): string =>
  m.principalType === 'USER' ? (m.username ?? 'Unknown user') : `${m.groupName} (${m.providerId})`;

export interface MemberRows {
  /** The roles a member may be given: the team-assignable ones. */
  roleOptions: { value: string; label: string }[];
  /** Whether the caller may change or remove this member; a directory group needs `user:admin`. */
  editable: (member: MemberView) => boolean;
  /** The member whose role is being saved, if any. */
  busyId: string | undefined;
  onChangeRole: (member: MemberView, roleId: string) => void;
  onRemove: (member: MemberView) => void;
}

/** A team's members: who, user or group, the role they hold (changeable in place), and the removal. */
export function memberColumns({
  roleOptions,
  editable,
  busyId,
  onChangeRole,
  onRemove,
}: MemberRows): Column<MemberView>[] {
  return [
    { id: 'member', header: 'Member', accessor: memberName, kind: 'identifier', priority: 'essential' },
    {
      id: 'type',
      header: 'Type',
      accessor: (m) => (m.principalType === 'USER' ? 'User' : 'Group'),
      kind: 'status',
      priority: 'low',
    },
    {
      id: 'role',
      header: 'Role',
      accessor: (m) => m.roleName,
      cell: (m) => (
        <Select
          aria-label={`Role of ${memberName(m)}`}
          data={
            roleOptions.some((o) => o.value === m.roleId)
              ? roleOptions
              : [{ value: m.roleId, label: m.roleName }, ...roleOptions]
          }
          value={m.roleId}
          disabled={!editable(m) || busyId === m.id}
          allowDeselect={false}
          onChange={(roleId) => roleId && roleId !== m.roleId && onChangeRole(m, roleId)}
        />
      ),
      kind: 'text',
      priority: 'essential',
    },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Remove',
      cell: (m) => (
        <ActionIcon
          variant="subtle"
          disabled={!editable(m)}
          onClick={() => onRemove(m)}
          aria-label={`Remove ${memberName(m)}`}
        >
          <IconTrash size="1rem" aria-hidden />
        </ActionIcon>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

export interface ShareRows {
  clusterName: (clusterId: string) => string;
  /** `out` lists who the team shares with; `in` lists who shares with the team, and has no removal. */
  direction: 'out' | 'in';
  editable: boolean;
  onRemove: (share: ShareView) => void;
}

/** A team's shares. A share whose pattern the owner no longer covers says so in words: it grants nothing. */
export function shareColumns({ clusterName, direction, editable, onRemove }: ShareRows): Column<ShareView>[] {
  const other = (s: ShareView) => (direction === 'out' ? s.targetTeamName : s.ownerTeamName);
  const columns: Column<ShareView>[] = [
    {
      id: 'team',
      header: direction === 'out' ? 'Shared with' : 'Shared by',
      accessor: other,
      kind: 'text',
      priority: 'essential',
    },
    { id: 'cluster', header: 'Cluster', accessor: (s) => clusterName(s.clusterId), kind: 'text', priority: 'high' },
    { id: 'kind', header: 'Kind', accessor: (s) => KIND_WORDS[s.kind], kind: 'text', priority: 'low' },
    { id: 'pattern', header: 'Pattern', accessor: (s) => s.pattern, kind: 'code', priority: 'essential' },
    { id: 'role', header: 'Role', accessor: (s) => s.roleName, kind: 'text', priority: 'high' },
    {
      id: 'covered',
      header: 'Status',
      accessor: (s) => (s.covered ? 'Active' : 'Not covered'),
      cell: (s) =>
        s.covered ? <StatusBadge>Active</StatusBadge> : <StatusBadge tone="warning">Not covered</StatusBadge>,
      kind: 'status',
      badge: true,
      priority: 'essential',
    },
  ];
  if (direction === 'in') return columns;
  return [
    ...columns,
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Remove',
      cell: (s) => (
        <ActionIcon
          variant="subtle"
          disabled={!editable}
          onClick={() => onRemove(s)}
          aria-label={`Remove share of ${s.pattern} with ${s.targetTeamName}`}
        >
          <IconTrash size="1rem" aria-hidden />
        </ActionIcon>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

export interface UnownedRows {
  editable: boolean;
  onAssign: (name: string) => void;
}

/** The names no team owns, each with the way to give it to this team. */
export function unownedColumns({ editable, onAssign }: UnownedRows): Column<UnownedView>[] {
  return [
    { id: 'name', header: 'Name', accessor: (u) => u.name, kind: 'identifier', priority: 'essential' },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Assign to this team',
      cell: (u) => (
        <Button
          variant="subtle"
          size="compact-xs"
          disabled={!editable}
          aria-label={`Assign ${u.name} to this team`}
          onClick={() => onAssign(u.name)}
        >
          Assign to this team
        </Button>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}

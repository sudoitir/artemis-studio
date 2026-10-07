import { Anchor, Button, Code, Group, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from '@tanstack/react-router';

import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { Stat } from '../../ui/Stat.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { clusterKey, request } from '../api/request.ts';
import { requestAll } from '../api/paging.ts';
import type { components } from '../api/schema.d.ts';
import { useCan } from '../auth/useCan.ts';
import { useSlot } from '../slots.ts';
import { Ago } from '../time/Ago.tsx';
import { absoluteLabel, useServerNow } from '../time/time.ts';
import { useDisplayZone } from '../time/timezone.ts';
import views from '../shell/Views.module.css';
import { isClosed, useHeldOperation, type HeldOperationDetail } from './api.ts';
import classes from './Approvals.module.css';
import { CancelRequest } from './CancelRequest.tsx';
import { changeColumns, namesTargets, rowText } from './columns.tsx';
import { DecisionPanel } from './DecisionPanel.tsx';
import { ExpiresIn } from './ExpiresIn.tsx';
import { AUTH_KIND, STATE, TRAIT, eventWord } from './words.ts';

type ClusterDetail = components['schemas']['ClusterDetail'];
type EnvironmentView = components['schemas']['EnvironmentView'];

/**
 * One approval request: what it will do, who asked and why, what happened to it, and — for an approver while it
 * waits — the decision. Generic: every gated operation, Studio's or a plugin's, reads the same way, and an
 * installed approval provider adds its own controls through the `approval.decision` slot.
 */
export function ApprovalView() {
  const { id } = useParams({ strict: false }) as { id: string };
  const held = useHeldOperation(id);

  let body;
  if (held.isPending) {
    body = <LoadingState label="Loading the request" blockSize="24rem" />;
  } else if (held.isError && held.error.status === 404) {
    body = (
      <>
        <PageHeader title="Request not found" />
        <EmptyState
          kind="empty"
          title="This request does not exist or you cannot see it"
          description="A request is shown only to the person who made it and to those who may decide it. Check the link, or find the request in your list."
          action={
            <Button component={Link} to="/approvals" variant="default" size="xs">
              Go to approval requests
            </Button>
          }
        />
      </>
    );
  } else if (held.isError) {
    body = (
      <>
        <PageHeader title="Approval request" />
        <ErrorState error={held.error} onRetry={() => void held.refetch()} />
      </>
    );
  } else {
    body = <Request detail={held.data} refresh={() => void held.refetch()} />;
  }

  return (
    <div className={views.page}>
      <div className={classes.column}>
        <Page>{body}</Page>
      </div>
    </div>
  );
}

function Request({ detail, refresh }: Readonly<{ detail: HeldOperationDetail; refresh: () => void }>) {
  const { operation } = detail;
  const state = STATE[operation.state];
  const now = useServerNow(30_000);
  useDisplayZone();
  const decision = useSlot('approval.decision');

  return (
    <>
      <PageHeader
        title={operation.summary}
        description="An operation held until a second person approves it."
        meta={
          <Group gap="xs" wrap="wrap">
            <StatusBadge tone={state.tone}>{state.word}</StatusBadge>
            <Text size="sm" c="dimmed">
              Requested by {operation.requesterUsername}, <Ago at={operation.requestedAt} now={now} />
            </Text>
          </Group>
        }
        actions={
          detail.canCancel ? <CancelRequest id={operation.id} summary={operation.summary} detail={detail} /> : null
        }
      />

      <Outcome detail={detail} />

      <Section title="What will happen">
        <WhatHappens detail={detail} />
      </Section>

      <Section title="Request">
        {detail.requesterLacksPermission && !isClosed(operation.state) ? (
          <Notice tone="warning" title="The requester lost the permission">
            {operation.requesterUsername} no longer holds the permission this operation needs. Studio checks it again
            before running, so if it is approved it is refused then and nothing changes.
          </Notice>
        ) : null}
        <DescriptionList items={requestItems(detail, refresh)} />
      </Section>

      {operation.state === 'HELD' ? (
        <Section title="Decision">
          {decision.map(({ id, Component }) => (
            <Component key={id} heldOperation={detail} refresh={refresh} />
          ))}
          <DecisionPanel detail={detail} refresh={refresh} />
        </Section>
      ) : null}

      <Section title="Timeline">
        <Timeline detail={detail} now={now} />
      </Section>
    </>
  );
}

/** What became of the request, said first once there is something to say. */
function Outcome({ detail }: Readonly<{ detail: HeldOperationDetail }>) {
  const { operation } = detail;
  const approver = operation.approverUsername ?? 'An approver';
  const outcome = detail.outcomeDetail;
  switch (operation.state) {
    case 'HELD':
      return detail.mine ? (
        <Notice tone="info" title="Waiting for approval">
          It runs only once someone else approves it. You are told in your inbox when it is decided.
        </Notice>
      ) : null;
    case 'APPROVED':
      return detail.mode === 'BY_REQUESTER' && detail.mine ? (
        <Notice tone="info" title="Approved: complete it yourself">
          {approver} approved it. Submit the same operation again before the request expires to run it; its result is
          shown only to you.
        </Notice>
      ) : (
        <Notice tone="neutral" title="Approved">
          {detail.mode === 'BY_REQUESTER'
            ? `${approver} approved it. ${operation.requesterUsername} completes it.`
            : `${approver} approved it. Studio runs it next.`}
        </Notice>
      );
    case 'EXECUTING':
      return (
        <Notice tone="neutral" title="Running">
          {approver} approved it, and Studio is running it now.
        </Notice>
      );
    case 'SUCCEEDED':
      return (
        <Notice tone="neutral" title="Succeeded">
          {outcome ?? 'It ran as requested.'}
        </Notice>
      );
    case 'FAILED':
      return (
        <Notice tone="danger" title="Failed">
          {outcome ?? 'It ran and failed.'} Check the target, then request it again if it is still needed.
        </Notice>
      );
    case 'REFUSED':
      return (
        <Notice tone="danger" title="Refused when run">
          {outcome ?? 'Studio checked it again before running it and refused.'} Nothing was changed.
        </Notice>
      );
    case 'OUTCOME_UNKNOWN':
      return (
        <Notice tone="warning" title="Outcome unknown">
          {outcome ? `${outcome} ` : ''}Studio started it but cannot tell whether it finished, so it will never run it
          again. Check the target before requesting it again.
        </Notice>
      );
    case 'REJECTED':
      return (
        <Notice tone="neutral" title="Rejected">
          {detail.decisionReason ? `${approver} rejected it: “${detail.decisionReason}”` : `${approver} rejected it.`}{' '}
          It will not run.
        </Notice>
      );
    case 'CANCELLED':
      return (
        <Notice tone="neutral" title="Cancelled">
          {operation.requesterUsername} cancelled it. It will not run.
        </Notice>
      );
    case 'EXPIRED':
      return (
        <Notice tone="neutral" title="Expired">
          Nobody decided it in time, so it will not run. Request it again if it is still needed.
        </Notice>
      );
  }
}

/** The changes, the estimated effect and where it acts. */
function WhatHappens({ detail }: Readonly<{ detail: HeldOperationDetail }>) {
  const { display, effect } = detail;
  const targets = namesTargets(display);
  let changes = null;
  if (display.length > 0 && targets) {
    changes = <DescriptionList items={display.map((row) => ({ term: row.label, value: rowText(row, true) }))} />;
  } else if (display.length > 0) {
    changes = (
      <DataTable
        variant="static"
        columnsMenu={false}
        label="Changes"
        columns={changeColumns()}
        data={display}
        rowKey={(r) => r.label}
        height={{ maxRows: display.length }}
        empty={null}
      />
    );
  }
  return (
    <>
      {changes}
      <Stat
        label="Affects"
        value={effect ? effect.count.toLocaleString('en-US') : null}
        unit={effect?.unit}
        unavailableReason="Studio could not estimate the effect. Read the changes above before deciding."
      />
      {effect?.detail ? <Text size="sm">{effect.detail}</Text> : null}
      <DescriptionList items={scopeItems(detail)} />
    </>
  );
}

/** Where the request acts: its cluster and environment by name where they can be read, and its kind. */
function scopeItems(detail: HeldOperationDetail): DescriptionItem[] {
  const { operation } = detail;
  const items: DescriptionItem[] = [];
  if (operation.clusterId) items.push({ term: 'Cluster', value: <ClusterName id={operation.clusterId} /> });
  if (detail.environmentId) items.push({ term: 'Environment', value: <EnvironmentName id={detail.environmentId} /> });
  if (!operation.clusterId && !detail.environmentId) items.push({ term: 'Scope', value: 'This Studio installation' });
  items.push({
    term: 'Operation',
    value: (
      <Code>
        {operation.type} v{detail.typeVersion}
      </Code>
    ),
  });
  if (detail.traits.length > 0) {
    items.push({ term: 'Kind of change', value: detail.traits.map((t) => TRAIT[t] ?? t).join(', ') });
  }
  return items;
}

/** A cluster's name, linked to it; its id when the name cannot be read. */
function ClusterName({ id }: Readonly<{ id: string }>) {
  const cluster = useQuery({
    queryKey: clusterKey(id),
    queryFn: () => request<ClusterDetail>(`/clusters/${id}`),
    retry: false,
  });
  return (
    <Anchor component={Link} to={`/clusters/${id}`} size="sm">
      {cluster.data?.name ?? id}
    </Anchor>
  );
}

/** An environment's name, for someone who may read environments; its id otherwise. */
function EnvironmentName({ id }: Readonly<{ id: string }>) {
  const { can } = useCan();
  const environments = useQuery({
    queryKey: ['environments'],
    queryFn: () => requestAll<EnvironmentView>('/environments'),
    enabled: can('environment:read'),
  });
  return <>{environments.data?.find((e) => e.id === id)?.name ?? id}</>;
}

function requestItems(detail: HeldOperationDetail, refresh: () => void): DescriptionItem[] {
  const { operation, policy } = detail;
  const items: DescriptionItem[] = [
    { term: 'Requested by', value: operation.requesterUsername },
    { term: 'Signed in with', value: AUTH_KIND[operation.authKind] },
    { term: 'Requested', value: absoluteLabel(operation.requestedAt) },
    { term: 'Reason', value: detail.reason || 'None given' },
    { term: 'Policy', value: policy.name ?? policy.id, hint: `Version ${policy.version}` },
  ];
  if (detail.approverHint) items.push({ term: 'Who can approve', value: detail.approverHint });
  if (operation.state === 'HELD') {
    items.push({ term: 'Expires', value: <ExpiresIn at={operation.expiresAt} onExpired={refresh} /> });
  } else if (operation.state === 'APPROVED' && detail.mode === 'BY_REQUESTER') {
    items.push({ term: 'Complete before', value: <ExpiresIn at={operation.expiresAt} onExpired={refresh} /> });
  }
  if (operation.approverUsername) {
    items.push({
      term: operation.state === 'REJECTED' ? 'Rejected by' : 'Approved by',
      value: operation.approverUsername,
      hint: operation.decidedAt ? absoluteLabel(operation.decidedAt) : undefined,
    });
  }
  if (detail.decisionReason) items.push({ term: 'Decision reason', value: detail.decisionReason });
  if (operation.finishedAt) items.push({ term: 'Finished', value: absoluteLabel(operation.finishedAt) });
  return items;
}

/** Everything that happened to the request, oldest first. */
function Timeline({ detail, now }: Readonly<{ detail: HeldOperationDetail; now: number }>) {
  const events = [...detail.events].sort((a, b) => a.seq - b.seq);
  if (events.length === 0) return <Text size="sm">Nothing is recorded for this request yet.</Text>;
  return (
    <ol className={classes.timeline} aria-label="Timeline">
      {events.map((event) => (
        <li key={event.seq} className={classes.event}>
          <Text size="sm">
            <Text span fw={600} size="sm">
              {eventWord(event)}
            </Text>{' '}
            by {event.actorUsername ?? 'Studio'}
          </Text>
          <Text size="sm" c="dimmed">
            <Ago at={event.at} now={now} />
          </Text>
          {event.detail ? (
            <Text size="sm" className={classes.eventDetail}>
              {event.detail}
            </Text>
          ) : null}
        </li>
      ))}
    </ol>
  );
}

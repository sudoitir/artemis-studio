import { useState } from 'react';
import { Button, Chip, Group, Switch, Text } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { absoluteLabel, elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { useRunSetupReview, useSetupReview, type SetupFindingView, type SetupReviewView as Review } from './api.ts';
import { AcceptRiskDialog } from './AcceptRiskDialog.tsx';
import type { SetupReviewSearch } from './feature.ts';
import { FindingCard } from './FindingCard.tsx';
import classes from './SetupReview.module.css';
import {
  CATEGORY_LABELS,
  CATEGORY_ORDER,
  SEVERITY_FILTERS,
  SEVERITY_MEANING,
  SEVERITY_WORDS,
  plural,
  severityTone,
} from './words.ts';

const RUN: ActionVerb = { verb: 'Run', past: 'Ran', progressive: 'Running' };

const DESCRIPTION =
  'This cluster’s HA, clustering, durability and message-safety configuration, checked against known mistakes. Read-only: one batched read per node, every 15 minutes or on demand. Nothing is changed on a broker.';

/** Why no finding is listed: the filter hides them, or nothing was found (which is not proof of a sound setup). */
function NoFindings({
  review: v,
  filtered,
  onClear,
}: Readonly<{ review: Review; filtered: boolean; onClear: () => void }>) {
  if (filtered || v.findings.length > 0) {
    return (
      <EmptyState
        kind="filtered"
        title="No findings match this filter"
        description={`${plural(v.findings.length, 'finding')} in the review. Clear the filter to see them.`}
        onClearFilters={onClear}
      />
    );
  }
  return (
    <EmptyState
      kind="empty"
      title="No findings"
      description={`None of the ${v.rulesInCatalogue} rules found a mistake in what the nodes reported. That is not proof of a sound setup: settings the management API does not expose, such as network-check-list, cannot be reviewed.`}
    />
  );
}

/** The nodes the review could not read, and what that means for the cluster-wide rules. */
function UnreviewedNodes({ review: v, unreviewed }: Readonly<{ review: Review; unreviewed: Review['nodes'] }>) {
  return (
    <Section
      title={`${plural(unreviewed.length, 'node')} not reviewed`}
      description={
        v.clusterEvaluated
          ? 'Cluster-wide rules still ran: every live node answered.'
          : 'Cluster-wide rules — quorum, version skew — were not evaluated, because a live node did not answer. Its earlier findings are kept and marked as not re-checked.'
      }
    >
      <DescriptionList
        label="Nodes that were not reviewed"
        items={unreviewed.map((n) => ({ term: n.nodeName, value: n.reason ?? 'no reason recorded' }))}
      />
    </Section>
  );
}

function openSummary(v: Review): string {
  if (v.open.critical + v.open.warning + v.open.info === 0) return 'No open findings.';
  return `Open: ${plural(v.open.critical, 'critical')}, ${plural(v.open.warning, 'warning')}, ${v.open.info} info.`;
}

/** What the review covered and when, so "nothing found" is read against how much was looked at. */
function Coverage({ review: v, now }: Readonly<{ review: Review; now: number }>) {
  const items: DescriptionItem[] = [
    {
      term: 'Reviewed',
      value: `${elapsedLabel(now - Date.parse(v.reviewedAt ?? ''))} ago`,
      hint: absoluteLabel(v.reviewedAt),
    },
    { term: 'Nodes read', value: `${v.nodesReviewed} of ${plural(v.nodesTotal, 'node')}` },
    { term: 'Rules checked', value: String(v.rulesInCatalogue) },
    {
      term: 'Findings',
      value: openSummary(v),
      hint: v.accepted > 0 ? `${plural(v.accepted, 'finding')} accepted as a known risk.` : undefined,
    },
  ];
  return (
    <>
      <DescriptionList label="Review coverage" columns={2} items={items} />
      {v.notice ? <Text size="sm">{v.notice}</Text> : null}
    </>
  );
}

/**
 * A cluster's setup review (cluster-setup-review spec, ADR-0106): what is known to go wrong
 * with this configuration, worst first, each with the evidence per node and the fix.
 *
 * Honest about coverage: a node that did not answer is named, a finding it could not
 * re-check is marked, and "nothing found" says how many rules were checked, never that the
 * cluster is healthy. Filters live in the URL, so a review can be shared as it is seen.
 */
export function SetupReviewView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as SetupReviewSearch;
  const navigate = useNavigate();
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  useDisplayZone();
  const review = useSetupReview(clusterId);
  const run = useRunSetupReview(clusterId);
  const now = useServerNow(15_000);
  const { can, loading: grantsLoading } = useCan();
  const canAccept = grantsLoading || can('alert:write', clusterId);
  const [accepting, setAccepting] = useState<SetupFindingView | null>(null);

  const setSearch = (next: Partial<SetupReviewSearch>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...next }) });

  const subject = 'the setup review';
  const runNow = () =>
    run.mutate(undefined, {
      // Too soon after the last review the server answers with it and a notice: nothing ran.
      onSuccess: (v) =>
        v.notice
          ? notify.failed({ action: RUN, subject, cause: v.notice, next: 'The last review is shown.' })
          : notify.succeeded({ action: RUN, subject }),
      onError: (e) =>
        notify.failed({
          action: RUN,
          subject,
          cause: e.message,
          next: 'Nothing was changed on a broker. Try again.',
        }),
    });

  const header = (
    <PageHeader
      title="Setup review"
      description={DESCRIPTION}
      actions={
        <Button onClick={runNow} loading={run.isPending} variant="default">
          Review now
        </Button>
      }
    />
  );

  if (review.isPending) {
    return (
      <Page>
        {header}
        <LoadingState label="Loading the review" blockSize="16rem" />
      </Page>
    );
  }
  if (review.isError) {
    return (
      <Page>
        {header}
        <ErrorState error={review.error} onRetry={() => void review.refetch()} />
      </Page>
    );
  }

  const v = review.data;
  if (!v.reviewedAt) {
    return (
      <Page>
        {header}
        <EmptyState
          kind="empty"
          title="Not reviewed yet"
          description={`Studio reviews every cluster shortly after it starts and then every 15 minutes, checking ${v.rulesInCatalogue} rules — among them a single replication pair that cannot win a quorum vote, redistribution left disabled, a connector advertising localhost, and a missing dead-letter address. Run it now to see this cluster’s result.`}
        />
      </Page>
    );
  }

  const acceptedActive = (f: SetupFindingView) => Boolean(f.acceptance?.active);
  const bySeverity = v.findings.filter((f) => !search.severity || f.severity === search.severity);
  const open = bySeverity.filter((f) => !acceptedActive(f));
  const acceptedList = search.accepted === 'hide' ? [] : bySeverity.filter(acceptedActive);
  const unreviewed = v.nodes.filter((n) => !n.reviewed);
  const filtered = Boolean(search.severity) || search.accepted === 'hide';
  const byCategory = CATEGORY_ORDER.map((c) => ({ category: c, items: open.filter((f) => f.category === c) })).filter(
    (g) => g.items.length > 0,
  );
  const card = (f: SetupFindingView) => (
    <FindingCard
      key={`${f.code}|${f.subject}`}
      finding={f}
      clusterId={clusterId}
      canAccept={canAccept}
      onAccept={() => setAccepting(f)}
    />
  );

  return (
    <Page>
      {header}

      <Coverage review={v} now={now} />

      {unreviewed.length > 0 ? <UnreviewedNodes review={v} unreviewed={unreviewed} /> : null}

      <Toolbar
        label="Review filters"
        start={
          <>
            <Chip.Group
              value={search.severity ?? 'ALL'}
              onChange={(value) =>
                setSearch({ severity: value === 'ALL' ? undefined : (value as SetupReviewSearch['severity']) })
              }
            >
              <Group gap="xs" role="radiogroup" aria-label="Severity">
                <Chip value="ALL" size="xs">
                  All
                </Chip>
                {SEVERITY_FILTERS.map((s) => (
                  <Chip key={s} value={s} size="xs">
                    {SEVERITY_WORDS[s]}
                  </Chip>
                ))}
              </Group>
            </Chip.Group>
            <Switch
              size="xs"
              label="Hide accepted risks"
              checked={search.accepted === 'hide'}
              onChange={(e) => setSearch({ accepted: e.currentTarget.checked ? 'hide' : undefined })}
            />
          </>
        }
      />
      <ul className={classes.legend} aria-label="What each severity means">
        {SEVERITY_FILTERS.map((s) => (
          <li key={s}>
            <StatusBadge tone={severityTone(s)}>{SEVERITY_WORDS[s]}</StatusBadge> {SEVERITY_MEANING[s]}
          </li>
        ))}
      </ul>
      {canAccept ? null : (
        <Text size="sm">
          Accepting a risk silences its alert, so it needs the alert:write permission on this cluster.
        </Text>
      )}

      {open.length === 0 && acceptedList.length === 0 ? (
        <NoFindings
          review={v}
          filtered={filtered}
          onClear={() => setSearch({ severity: undefined, accepted: undefined })}
        />
      ) : (
        <>
          {byCategory.map((group) => (
            <Section
              key={group.category}
              title={`${CATEGORY_LABELS[group.category] ?? group.category} (${group.items.length})`}
            >
              {group.items.map(card)}
            </Section>
          ))}
          {acceptedList.length > 0 ? (
            <Section title={`Accepted as known risks (${acceptedList.length})`}>{acceptedList.map(card)}</Section>
          ) : null}
        </>
      )}

      {v.notAssessed.length > 0 ? (
        <details>
          <summary>
            <Text span size="sm">
              {plural(v.notAssessed.length, 'rule group')} not assessed
            </Text>
          </summary>
          <DescriptionList
            label="Rule groups not assessed"
            items={v.notAssessed.map((n) => ({
              term: `${n.subjectLabel} — ${n.code === '*' ? 'all rules' : n.code}`,
              value: n.reason ?? 'no reason recorded',
            }))}
          />
        </details>
      ) : null}

      <AcceptRiskDialog clusterId={clusterId} finding={accepting} onClose={() => setAccepting(null)} />
    </Page>
  );
}

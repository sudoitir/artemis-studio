import { useState } from 'react';
import {
  Alert,
  Anchor,
  Button,
  Chip,
  Group,
  Stack,
  Switch,
  Text,
  Title,
} from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { absoluteLabel, elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { useRunSetupReview, useSetupReview, type SetupFindingView } from './api.ts';
import { AcceptRiskDialog } from './AcceptRiskDialog.tsx';
import type { SetupReviewSearch } from './feature.ts';
import { FindingCard } from './FindingCard.tsx';
import { CATEGORY_LABELS, CATEGORY_ORDER, SEVERITY_FILTERS, SEVERITY_WORDS, plural } from './words.ts';

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
  const review = useSetupReview(clusterId);
  const run = useRunSetupReview(clusterId);
  const now = useServerNow(15_000);
  const { can, loading: grantsLoading } = useCan();
  const canAccept = grantsLoading || can('alert:write', clusterId);
  const [accepting, setAccepting] = useState<SetupFindingView | null>(null);
  const [announcement, setAnnouncement] = useState('');

  const setSearch = (next: Partial<SetupReviewSearch>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...next }) });

  const runNow = () =>
    run.mutate(undefined, {
      onSuccess: (v) =>
        setAnnouncement(
          v.notice ??
            `Review finished: ${plural(v.open.critical, 'critical')}, ${plural(v.open.warning, 'warning')}, ${v.open.info} info.`,
        ),
      onError: (e) => setAnnouncement(`The review did not run: ${e.message}`),
    });

  const header = (
    <Group justify="space-between" align="flex-start">
      <Stack gap={2}>
        <Title order={3}>Setup review</Title>
        <Text size="sm" c="dimmed" maw={720}>
          This cluster’s HA, clustering, durability and message-safety configuration, checked against known mistakes.
          Read-only: one batched read per node, every 15 minutes or on demand. Nothing is changed on a broker.
        </Text>
      </Stack>
      <Button onClick={runNow} loading={run.isPending} variant="default">
        Review now
      </Button>
    </Group>
  );

  const live = (
    <div role="status" aria-live="polite" aria-label="Setup review outcome">
      {announcement ? <Text size="sm">{announcement}</Text> : null}
    </div>
  );

  if (review.isPending) {
    return (
      <Stack gap="md">
        {header}
        <Text size="sm" c="dimmed">
          Loading the review…
        </Text>
      </Stack>
    );
  }
  if (review.isError) {
    return (
      <Stack gap="md">
        {header}
        <Alert color="red" variant="light" title="The review could not be loaded">
          {review.error.message}. Reload the page; if it persists, check that Studio can reach its database.
        </Alert>
      </Stack>
    );
  }

  const v = review.data;
  if (!v.reviewedAt) {
    return (
      <Stack gap="md">
        {header}
        {live}
        <Alert variant="light" color="gray" title="Not reviewed yet">
          Studio reviews every cluster shortly after it starts and then every 15 minutes, checking {v.rulesInCatalogue}{' '}
          rules — among them a single replication pair that cannot win a quorum vote, redistribution left disabled, a
          connector advertising localhost, and a missing dead-letter address. Run it now to see this cluster’s result.
        </Alert>
      </Stack>
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
      announce={setAnnouncement}
    />
  );

  return (
    <Stack gap="md">
      {header}
      {live}

      <Text size="sm" style={{ fontVariantNumeric: 'tabular-nums' }}>
        Reviewed {elapsedLabel(now - Date.parse(v.reviewedAt))} ago ({absoluteLabel(v.reviewedAt)}) ·{' '}
        {v.nodesReviewed} of {plural(v.nodesTotal, 'node')} read · {v.rulesInCatalogue} rules checked
      </Text>
      {v.notice ? (
        <Text size="sm" c="dimmed">
          {v.notice}
        </Text>
      ) : null}

      <Text size="md" fw={600}>
        {v.open.critical + v.open.warning + v.open.info === 0
          ? 'No open findings.'
          : `Open: ${plural(v.open.critical, 'critical')}, ${plural(v.open.warning, 'warning')}, ${v.open.info} info.`}
        {v.accepted > 0 ? ` ${plural(v.accepted, 'finding')} accepted as a known risk.` : ''}
      </Text>

      {unreviewed.length > 0 ? (
        <Alert color="yellow" variant="light" title={`${plural(unreviewed.length, 'node')} not reviewed`}>
          <Stack gap={4}>
            {unreviewed.map((n) => (
              <Text size="sm" key={n.nodeId}>
                <Text span fw={600}>
                  {n.nodeName}
                </Text>
                : {n.reason ?? 'no reason recorded'}
              </Text>
            ))}
            <Text size="sm">
              {v.clusterEvaluated
                ? 'Cluster-wide rules still ran: every live node answered.'
                : 'Cluster-wide rules — quorum, version skew — were not evaluated, because a live node did not answer. Its earlier findings are kept and marked as not re-checked.'}
            </Text>
          </Stack>
        </Alert>
      ) : null}

      <Group gap="sm" align="center">
        <Chip.Group
          value={search.severity ?? 'ALL'}
          onChange={(value) => setSearch({ severity: value === 'ALL' ? undefined : (value as SetupReviewSearch['severity']) })}
        >
          <Group gap={6} role="radiogroup" aria-label="Severity">
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
      </Group>

      {open.length === 0 && acceptedList.length === 0 ? (
        filtered || v.findings.length > 0 ? (
          <Text size="sm" c="dimmed">
            No findings match this filter
            {v.findings.length > 0 ? ` (${plural(v.findings.length, 'finding')} in the review)` : ''}.{' '}
            <Anchor component="button" type="button" size="sm" onClick={() => setSearch({ severity: undefined, accepted: undefined })}>
              Clear the filter
            </Anchor>
          </Text>
        ) : (
          <Text size="sm" c="dimmed">
            None of the {v.rulesInCatalogue} rules found a mistake in what the nodes reported. That is not proof of a
            sound setup: settings the management API does not expose, such as network-check-list, cannot be reviewed.
          </Text>
        )
      ) : (
        <>
          {byCategory.map((group) => (
            <Stack gap="xs" key={group.category}>
              <Title order={4}>
                {CATEGORY_LABELS[group.category] ?? group.category}{' '}
                <Text span size="sm" c="dimmed" fw={400}>
                  ({group.items.length})
                </Text>
              </Title>
              {group.items.map(card)}
            </Stack>
          ))}
          {acceptedList.length > 0 ? (
            <Stack gap="xs">
              <Title order={4}>
                Accepted as known risks{' '}
                <Text span size="sm" c="dimmed" fw={400}>
                  ({acceptedList.length})
                </Text>
              </Title>
              {acceptedList.map(card)}
            </Stack>
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
          <Stack gap={2} mt="xs">
            {v.notAssessed.map((n, i) => (
              <Text size="xs" c="dimmed" key={`${n.code}|${n.subject}|${i}`}>
                {n.subjectLabel} — {n.code === '*' ? 'all rules' : n.code}: {n.reason ?? 'no reason recorded'}
              </Text>
            ))}
          </Stack>
        </details>
      ) : null}

      <AcceptRiskDialog
        clusterId={clusterId}
        finding={accepting}
        onClose={() => setAccepting(null)}
        announce={setAnnouncement}
      />
    </Stack>
  );
}

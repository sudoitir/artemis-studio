import {
  Alert,
  Badge,
  Button,
  CloseButton,
  Group,
  Paper,
  SegmentedControl,
  Select,
  Skeleton,
  Stack,
  Text,
  Title,
  VisuallyHidden,
} from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { useFlowGraph, type FlowGraphView } from './api.ts';
import { BrokerNodeNotices } from './BrokerNodeNotices.tsx';
import { FlowKpis } from './FlowKpis.tsx';
import { GROUP_LABELS, RANK_LABELS, totalRateLabel } from './flowFormat.ts';
import {
  DEFAULT_LIMIT,
  FLOW_GROUPINGS,
  FLOW_LIMITS,
  FLOW_RANKS,
  parseFocus,
  type FlowGroupBy,
  type FlowRank,
  type FlowSearch,
} from './flowSearch.ts';
import { FlowTable } from './FlowTable.tsx';
import classes from './FlowView.module.css';

/**
 * Flow: which clients produce to which addresses, how those route into queues, and who consumes
 * them — bounded to the busiest paths, focusable on any one resource, with every rate's source and
 * age stated (flow-visualization spec). Everything that describes the view lives in the URL.
 */
export function FlowView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as FlowSearch;
  const navigate = useNavigate();
  const graph = useFlowGraph(clusterId, search);

  const setSearch = (patch: Partial<Record<keyof FlowSearch, unknown>>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

  const focus = parseFocus(search.focus);
  const rank: FlowRank = search.rank ?? 'IN';
  const limit = search.limit ?? DEFAULT_LIMIT;

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-end" wrap="wrap" gap="sm">
        <div>
          <Title order={3}>Flow</Title>
          <Text size="sm" c="dimmed">
            Who produces to which address, how it routes into queues, and who consumes it.
          </Text>
        </div>
        <Group gap="sm" align="flex-end" wrap="wrap">
          <Select
            label="Rank paths by"
            size="xs"
            w={150}
            allowDeselect={false}
            data={FLOW_RANKS.map((value) => ({ value, label: RANK_LABELS[value] }))}
            value={rank}
            onChange={(value) => setSearch({ rank: value === 'IN' ? undefined : value })}
          />
          <Select
            label="Group clients by"
            size="xs"
            w={150}
            allowDeselect={false}
            data={FLOW_GROUPINGS.map((value) => ({ value, label: GROUP_LABELS[value] }))}
            value={search.groupBy ?? 'CLIENT_ID'}
            onChange={(value) => setSearch({ groupBy: value === 'CLIENT_ID' ? undefined : (value as FlowGroupBy) })}
          />
          <Select
            label="Show"
            size="xs"
            w={170}
            allowDeselect={false}
            data={FLOW_LIMITS.map((value) => ({ value: String(value), label: `${value} busiest paths` }))}
            value={String(limit)}
            onChange={(value) => setSearch({ limit: Number(value) === DEFAULT_LIMIT ? undefined : Number(value) })}
          />
        </Group>
      </Group>

      {focus ? (
        <Group gap="sm" wrap="wrap">
          <Badge
            size="lg"
            variant="light"
            color="gray"
            rightSection={
              <CloseButton
                size="xs"
                aria-label="Clear focus"
                onClick={() => setSearch({ focus: undefined, hops: undefined })}
              />
            }
          >
            Focused on {focus.kind} {focus.name}
          </Badge>
          <Group gap={6}>
            <Text size="xs" c="dimmed" id="flow-reach">
              Reach
            </Text>
            <SegmentedControl
              size="xs"
              aria-labelledby="flow-reach"
              data={[
                { value: '1', label: 'Neighbours' },
                { value: '2', label: '+1 hop' },
                { value: '3', label: '+2 hops' },
              ]}
              value={String(search.hops ?? 1)}
              onChange={(value) => setSearch({ hops: value === '1' ? undefined : Number(value) })}
            />
          </Group>
        </Group>
      ) : null}

      {graph.isError ? (
        <Alert color="red" variant="light" title={graph.error.title ?? 'Flow could not be read'}>
          <Stack gap="xs" align="flex-start">
            <Text size="sm">{graph.error.message}</Text>
            <Button size="xs" variant="default" onClick={() => graph.refetch()}>
              Try again
            </Button>
          </Stack>
        </Alert>
      ) : graph.data === undefined ? (
        <Stack gap="sm" aria-busy="true" aria-label="Loading flow">
          <Skeleton height={72} />
          <Skeleton height={360} />
        </Stack>
      ) : (
        <FlowBody
          data={graph.data}
          search={search}
          onSortChange={(sort) => setSearch({ sort })}
          onFocus={(next) => setSearch({ focus: next, hops: undefined })}
          onClearFocus={() => setSearch({ focus: undefined, hops: undefined })}
          rank={rank}
        />
      )}
    </Stack>
  );
}

function FlowBody({
  data,
  search,
  rank,
  onSortChange,
  onFocus,
  onClearFocus,
}: {
  data: FlowGraphView;
  search: FlowSearch;
  rank: FlowRank;
  onSortChange: (sort: string | undefined) => void;
  onFocus: (focus: string) => void;
  onClearFocus: () => void;
}) {
  const now = useServerNow();
  const totals = data.totals ?? { paths: 0, shown: 0, limit: DEFAULT_LIMIT, clamped: false };
  const kpis = data.kpis ?? { backlog: 0, clients: 0, faults: 0 };
  const brokerNodes = data.brokerNodes ?? [];
  const nothingAtAll = (totals.paths ?? 0) === 0 && (kpis.clients ?? 0) === 0 && !data.focus;

  return (
    <Stack gap="md">
      <FlowKpis kpis={kpis} />
      <BrokerNodeNotices nodes={brokerNodes} />

      {data.measuring ? (
        <Text size="sm" c="dimmed" role="status">
          Measuring client rates. The first rates appear after two samples, about{' '}
          {(data.sampleIntervalSeconds ?? 15) * 2} seconds after this view opened.
        </Text>
      ) : null}

      {data.focus && !data.focus.matched ? (
        <Alert variant="light" color="gray" title={`Nothing matches the focus ${data.focus.kind} ${data.focus.name}`}>
          <Stack gap="xs" align="flex-start">
            <Text size="sm">
              It may have been deleted, or its clients disconnected since the address was shared.
            </Text>
            <Button size="xs" variant="default" onClick={onClearFocus}>
              Clear focus
            </Button>
          </Stack>
        </Alert>
      ) : nothingAtAll ? (
        <Paper withBorder p="lg" radius="md">
          <Stack gap="xs" className={classes.empty}>
            <Title order={4}>No flow to show yet</Title>
            <Text size="sm">
              Flow draws the clients producing to each address, the queues those addresses route into, and the
              clients consuming them. This cluster has no queues Studio has seen and no connected producers or
              consumers.
            </Text>
            <Text size="sm" c="dimmed">
              Clients appear here within one sampling interval of attaching. New queues appear once the queue
              sweep has read them.
            </Text>
          </Stack>
        </Paper>
      ) : (
        <FlowTable graph={data} sort={search.sort} onSortChange={onSortChange} onFocus={onFocus} />
      )}

      <div className={classes.footer}>
        <Text size="xs" c="dimmed">
          Showing {totals.shown} of {totals.paths} {totals.paths === 1 ? 'path' : 'paths'}, ranked by{' '}
          {RANK_LABELS[rank]}.
        </Text>
        {(totals.shown ?? 0) < (totals.paths ?? 0) && !data.focus ? (
          <Text size="xs" c="dimmed">
            Raise the limit, or focus a client, address or queue to reach the rest.
          </Text>
        ) : null}
        {totals.clamped ? (
          <Text size="xs" c="dimmed">
            The server draws at most {totals.limit} paths.
          </Text>
        ) : null}
        <Text size="xs" c="dimmed">
          Totals cover every path.{' '}
          {data.sampledAt
            ? `Clients sampled ${elapsedLabel(now - Date.parse(data.sampledAt))} ago.`
            : 'Clients not sampled yet.'}
        </Text>
      </div>

      <VisuallyHidden role="status">
        {`${totals.shown} of ${totals.paths} paths shown. ${kpis.faults ?? 0} faults. Messages in ${totalRateLabel(
          kpis.inRate,
        )}.`}
      </VisuallyHidden>
    </Stack>
  );
}

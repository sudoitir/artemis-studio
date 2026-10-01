import { Alert, ColorSwatch, Stack, Text } from '@mantine/core';

import { useDismissedNotice } from '../../kernel/useDismissedNotice.ts';
import { useTitlePart } from '../../kernel/shell/pageTitle.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { useCluster, useEnvironments, type CapabilitiesView } from './api.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import styles from './ClusterHeader.module.css';

/** The capabilities the connection reports as unavailable. */
function capabilityGaps(caps: CapabilitiesView | undefined): string[] {
  if (!caps) return [];
  return (['managementRead', 'managementWrite', 'messageIo', 'notifications'] as const).filter(
    (k) => caps[k].status === 'UNAVAILABLE',
  );
}

/**
 * Above every view of a cluster (`cluster.header`): a context strip with the cluster's name, its
 * environment beside the environment's colour, and what is known of its health, then the health banner
 * and a notice for a capability the connection lacks.
 *
 * It holds no heading. Each view's `PageHeader` renders the page's one h1 (ADR-0162), so the cluster
 * is stated beside it as context, not as a second top-level heading. How current the data is stays
 * with the shell's freshness indicator, which answers it for every route.
 */
export function ClusterHeader({ clusterId }: Readonly<{ clusterId: string }>) {
  const { data, isPending, isError, error, refetch } = useCluster(clusterId);
  const environments = useEnvironments();
  // The cluster's name, for the shell's title and breadcrumb (ADR-0109).
  useTitlePart('cluster', data?.name);

  const caps = data?.capabilities;
  // Nag only on a real, actionable gap. UNKNOWN is not one: since ADR-0049 D5
  // managementWrite and messageIo stay UNKNOWN until a write has actually been
  // attempted, and a notice on every freshly registered cluster — for a broker
  // that is very likely fine — is noise the operator learns to dismiss unread.
  const gaps = capabilityGaps(caps);
  // Keyed on which capabilities are short, so dismissing today's gap does not
  // also hide a different one that appears tomorrow. Computed before the early
  // returns below so the hook order never depends on the query state.
  const [capsDismissed, dismissCaps] = useDismissedNotice(`capabilities:${clusterId}:${gaps.join(',')}`);

  if (isPending) return <LoadingState label="Loading the cluster" variant="inline" blockSize="1.5rem" />;
  if (isError) return <ErrorState error={error} onRetry={() => void refetch()} />;

  const environment = environments.data?.find((e) => e.id === data.environmentId);
  const nodeCount = data.topology.nodes.reduce((n, node) => n + node.endpoints.length, 0);
  const hasPair = data.topology.nodes.some((n) => n.endpoints.length > 1);
  const meta = [
    `${nodeCount} node${nodeCount === 1 ? '' : 's'}`,
    hasPair ? 'replication' : 'standalone',
    data.health.level === 'UNKNOWN' ? 'not yet contacted' : 'reachable',
  ].join(' · ');
  const critical = data.health.splitBrain === 'CRITICAL';

  return (
    <>
      <div role="group" aria-label={`Cluster ${data.name}`} className={styles.strip}>
        <Text component="span" size="md" className={styles.name}>
          {data.name}
        </Text>
        {environment ? (
          <Text component="span" size="sm" className={styles.environment}>
            <ColorSwatch component="span" color={environment.colour ?? 'var(--as-border)'} size="0.75rem" />
            {environment.name}
          </Text>
        ) : null}
        <Text component="span" size="sm" className={styles.meta}>
          {meta}
        </Text>
      </div>

      {data.health.level !== 'OK' && data.health.notes.length > 0 ? (
        <Alert
          variant="default"
          className={styles.notice}
          data-tone={critical ? 'danger' : 'warning'}
          role={critical ? 'alert' : undefined}
          title={critical ? 'Two nodes are live in one pair' : 'Needs attention'}
        >
          <Stack gap="xs">
            {data.health.notes.map((n) => (
              <Text key={n} size="sm">
                {n}
              </Text>
            ))}
          </Stack>
        </Alert>
      ) : null}

      {gaps.length > 0 && !capsDismissed ? (
        <Alert
          variant="default"
          title="Some broker capabilities need setup"
          withCloseButton
          closeButtonLabel="Dismiss until you sign out"
          onClose={dismissCaps}
        >
          <Stack gap="xs">
            <Text size="sm">
              One or more features are limited by this connection. Each row below expands with the reason and the{' '}
              <code>broker.xml</code> change that closes the gap.
            </Text>
            <CapabilityLedger capabilities={data.capabilities} clusterId={clusterId} />
          </Stack>
        </Alert>
      ) : null}
    </>
  );
}

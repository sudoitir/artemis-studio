import { useState } from 'react';
import { Alert, Button, Group, Skeleton, Stack, Tabs, Text, Tooltip } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import {
  useBrokerConfig,
  useBrokerConfigCatalogue,
  useBrokerConfigRecommendations,
  useEvaluateBrokerConfigDrift,
  type ConfigDeclarationView,
} from './api.ts';
import { useCluster } from '../clusters/index.ts';
import { absoluteLabel, elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import type { ConfigurationSearch } from './feature.ts';
import { AdoptionSuggestion } from './AdoptionSuggestion.tsx';
import { DeclaredTab } from './DeclaredTab.tsx';
import { HistoryTab } from './HistoryTab.tsx';
import { ModeControl } from './ModeControl.tsx';
import { NodesPanel } from './NodesPanel.tsx';
import { RecommendedConfiguration } from './RecommendedConfiguration.tsx';
import { APPLY_PERMISSION_LABEL, ReviewApplyDrawer, type ApplyScope } from './ReviewApplyDrawer.tsx';
import { AdoptDrawer, ExportXmlDrawer, ImportXmlDrawer } from './XmlDrawers.tsx';
import classes from './Configuration.module.css';
import { appliedWords, CONFIG_MANAGED_REASON } from './words.ts';

type Drawer = 'adopt' | 'import' | 'export' | null;

export const WRITE_PERMISSION_LABEL = 'Edit declared configuration';

/**
 * A cluster's declared configuration on one screen (ADR-0087 D1): what it should
 * run, what each live node runs, and the apply that closes the difference —
 * reviewed and confirmed in a drawer over the rows it changes, never at a
 * separate address.
 *
 * <p>The status bar is the sentence a save produces: "Revision 4 — applied to 0
 * of 2 live nodes". It is derived from the stored per-node evaluation, so an edit
 * needs no extra state to announce that no broker has it yet.
 */
export function ConfigurationView() {
  useDisplayZone();
  useServerNow();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as ConfigurationSearch;
  const navigate = useNavigate();
  const tab = search.tab ?? 'declared';

  const declaration = useBrokerConfig(clusterId);
  const catalogue = useBrokerConfigCatalogue(clusterId);
  const cluster = useCluster(clusterId);
  const recommendations = useBrokerConfigRecommendations(clusterId, tab === 'recommended');
  const { can, loading } = useCan();
  const [drawer, setDrawer] = useState<Drawer>(null);
  const [scope, setScope] = useState<ApplyScope | null>(null);

  const setSearch = (patch: Partial<ConfigurationSearch>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

  if (declaration.isError) {
    return (
      <Alert color="red" variant="light" title={declaration.error.title}>
        {declaration.error.message}
      </Alert>
    );
  }
  if (!declaration.data) {
    return (
      <Stack gap={4}>
        {Array.from({ length: 6 }).map((_, i) => (
          <Skeleton key={i} height={30} />
        ))}
      </Stack>
    );
  }

  const d = declaration.data;
  const canWrite = loading || can('config:write', clusterId);
  const writeGate = gateFor(can('config:write', clusterId), WRITE_PERMISSION_LABEL, undefined, loading);
  const applyPermission = gateFor(
    can('config:apply', clusterId),
    APPLY_PERMISSION_LABEL,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  const applyGate: GateVerdict =
    d.applyMode === 'CONFIG_MANAGED'
      ? { kind: 'blocked', reason: CONFIG_MANAGED_REASON }
      : !d.declared
        ? {
            kind: 'blocked',
            reason: 'Nothing is declared yet. Adopt from the cluster, import broker.xml or add an entry first.',
          }
        : applyPermission;
  const studioManaged = d.applyMode === 'STUDIO_MANAGED';

  const writeButton = (label: string, onClick: () => void) => (
    <CapabilityGate verdict={writeGate}>
      <Button variant="default" size="xs" onClick={onClick} disabled={writeGate.kind === 'blocked'}>
        {label}
      </Button>
    </CapabilityGate>
  );

  return (
    <Stack gap="md">
      <StatusBar
        declaration={d}
        applyGate={applyGate}
        studioManaged={studioManaged}
        onReview={() => setScope({})}
      />

      <Group justify="space-between" align="flex-start" wrap="wrap">
        {d.declared ? <ModeControl declaration={d} canWrite={canWrite} /> : <div />}
        <Group gap="xs" wrap="wrap">
          {writeButton('Adopt from cluster', () => setDrawer('adopt'))}
          {writeButton('Import XML', () => setDrawer('import'))}
          <Button
            variant={studioManaged ? 'default' : 'filled'}
            size="xs"
            onClick={() => setDrawer('export')}
            disabled={!d.declared}
            title={d.declared ? undefined : 'Nothing is declared yet.'}
          >
            {studioManaged ? 'Export XML' : 'Copy broker.xml fragment'}
          </Button>
        </Group>
      </Group>

      {!d.declared ? (
        <>
          <AdoptionSuggestion
            declaration={d}
            onAdopt={() => setDrawer('adopt')}
            canWrite={writeGate.kind !== 'blocked'}
            blockedReason={writeGate.kind === 'blocked' ? writeGate.reason : undefined}
          />
          <Alert variant="light" color="gray" title="Declare what this cluster should run">
            <Stack gap="xs">
              <Text size="sm">
                A declaration is the configuration Studio can apply over the management API and measure every live node
                against: addresses and queues, address settings, security settings and diverts. Nothing is declared for
                this cluster yet, so there is nothing to compare the nodes with.
              </Text>
              <Text size="sm">
                Start with <b>Adopt from cluster</b> to take what the brokers run today, <b>Import XML</b> to paste a
                broker.xml, or open a section below and add an entry. Static settings — the rest of broker.xml — cannot
                be applied over management and are not part of a declaration.
              </Text>
            </Stack>
          </Alert>
        </>
      ) : null}

      <Tabs value={tab} onChange={(next) => setSearch({ tab: (next as ConfigurationSearch['tab']) ?? undefined })}>
        <Tabs.List>
          <Tabs.Tab value="declared">Declared &amp; live</Tabs.Tab>
          <Tabs.Tab value="history">History</Tabs.Tab>
          <Tabs.Tab value="recommended">Recommended</Tabs.Tab>
        </Tabs.List>
      </Tabs>

      {tab === 'recommended' ? (
        <RecommendedTab
          clusterId={clusterId}
          query={recommendations}
          onReview={() => setScope({})}
          disabledReason={
            d.applyMode === 'CONFIG_MANAGED'
              ? CONFIG_MANAGED_REASON
              : writeGate.kind === 'blocked'
                ? writeGate.reason
                : undefined
          }
        />
      ) : tab === 'history' ? (
        <HistoryTab declaration={d} catalogue={catalogue.data} />
      ) : (
        <>
          <DeclaredTab
            declaration={d}
            catalogue={catalogue.data}
            canWrite={canWrite}
            applyGate={applyGate}
            onApply={setScope}
            openSection={search.section}
            openItem={search.item}
            onEdit={(section, item) => setSearch({ section, item })}
          />
          <NodesPanel declaration={d} catalogue={catalogue.data} />
        </>
      )}

      <ReviewApplyDrawer declaration={d} scope={scope} opened={scope !== null} onClose={() => setScope(null)} />
      <AdoptDrawer declaration={d} opened={drawer === 'adopt'} onClose={() => setDrawer(null)} />
      <ImportXmlDrawer declaration={d} opened={drawer === 'import'} onClose={() => setDrawer(null)} />
      <ExportXmlDrawer declaration={d} opened={drawer === 'export'} onClose={() => setDrawer(null)} />
    </Stack>
  );
}

/**
 * Where the declaration has got to, kept on screen (ADR-0087 D1). It carries the
 * one primary action, so a saved revision is never left with no way to reach a
 * broker; when applying is impossible the control stays visible and says why
 * (non-negotiable #5).
 */
function StatusBar({
  declaration,
  applyGate,
  studioManaged,
  onReview,
}: {
  declaration: ConfigDeclarationView;
  applyGate: GateVerdict;
  studioManaged: boolean;
  onReview: () => void;
}) {
  const evaluate = useEvaluateBrokerConfigDrift(declaration.clusterId);
  const applied = appliedWords(declaration);
  const latest = declaration.nodes
    .map((n) => n.evaluatedAt)
    .filter((t): t is string => !!t)
    .sort()
    .at(-1);

  return (
    <Stack gap={4}>
      <div className={classes.summaryBar}>
        <Stack gap={2}>
          <Text size="sm" fw={600} className={classes.state} data-tone={applied.tone} aria-live="polite">
            {applied.text}
          </Text>
          <Text size="xs" c="dimmed">
            {declaration.declared
              ? `Saved ${absoluteLabel(declaration.updatedAt)}${declaration.updatedBy ? ` by ${declaration.updatedBy}` : ''}${
                  declaration.source ? ` · ${declaration.source.toLowerCase().replace(/_/g, ' ')}` : ''
                } · `
              : ''}
            {latest ? (
              <Tooltip label={absoluteLabel(latest)} withArrow>
                <span tabIndex={0}>
                  nodes evaluated {elapsedLabel(Date.now() - Date.parse(latest))} ago, about every{' '}
                  {elapsedLabel(declaration.driftIntervalSeconds * 1_000)}
                </span>
              </Tooltip>
            ) : (
              'nodes not evaluated yet'
            )}
          </Text>
        </Stack>
        <Group gap="xs" wrap="wrap">
          <Button
            variant="default"
            size="xs"
            loading={evaluate.isPending}
            onClick={() => evaluate.mutate()}
            disabled={!declaration.declared}
            title={declaration.declared ? undefined : 'Nothing is declared to compare the nodes with.'}
          >
            Evaluate now
          </Button>
          <CapabilityGate verdict={applyGate}>
            <Button
              size="xs"
              variant={studioManaged ? 'filled' : 'default'}
              onClick={onReview}
              disabled={applyGate.kind === 'blocked'}
            >
              Review &amp; apply
            </Button>
          </CapabilityGate>
        </Group>
      </div>
      {evaluate.isError ? (
        <Alert color="red" variant="light" title={evaluate.error.title} role="alert">
          {evaluate.error.message}
        </Alert>
      ) : null}
    </Stack>
  );
}

/**
 * The recommendations tab. A probe of the live brokers, so it has a pending and
 * an unreachable state of its own: an empty panel would read as "nothing to
 * configure", which is the opposite of what an unreadable cluster means.
 */
function RecommendedTab({
  clusterId,
  query,
  onReview,
  disabledReason,
}: {
  clusterId: string;
  query: ReturnType<typeof useBrokerConfigRecommendations>;
  onReview: () => void;
  disabledReason?: string;
}) {
  if (query.isError) {
    return (
      <Alert color="red" variant="light" title={query.error.title}>
        {query.error.message} Studio could not assess this cluster, so it has nothing to recommend — this is not the
        same as having nothing to recommend.
      </Alert>
    );
  }
  if (!query.data) {
    return (
      <Stack gap={4}>
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} height={30} />
        ))}
      </Stack>
    );
  }
  return (
    <RecommendedConfiguration
      clusterId={clusterId}
      recommendations={query.data}
      onDeclared={onReview}
      disabledReason={disabledReason}
    />
  );
}

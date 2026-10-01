import { useState, type ReactNode } from 'react';
import { Button, Stack, Tabs, Text, Tooltip } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import {
  useBrokerConfig,
  useBrokerConfigCatalogue,
  useBrokerConfigRecommendations,
  useEvaluateBrokerConfigDrift,
  type ConfigDeclarationView,
} from './api.ts';
import { absoluteLabel, elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { ConfigurationSearch } from './feature.ts';
import { AdoptionSuggestion } from './AdoptionSuggestion.tsx';
import { DeclaredTab } from './DeclaredTab.tsx';
import { HistoryTab } from './HistoryTab.tsx';
import { ModeControl } from './ModeControl.tsx';
import { NodesPanel } from './NodesPanel.tsx';
import { RecommendedConfiguration } from './RecommendedConfiguration.tsx';
import { useDeclarationGates } from './gates.ts';
import { ReviewApplyDrawer, type ApplyScope } from './ReviewApplyDrawer.tsx';
import { AdoptDrawer, ExportXmlDrawer, ImportXmlDrawer } from './XmlDrawers.tsx';
import classes from './Configuration.module.css';
import { appliedWords, CONFIG_MANAGED_REASON } from './words.ts';

type Drawer = 'adopt' | 'import' | 'export' | null;

const EVALUATE: ActionVerb = { verb: 'Evaluate', past: 'Evaluated', progressive: 'Evaluating' };

const LEAD = 'What this cluster should run, what each live node runs, and the apply that closes the difference.';

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
  const recommendations = useBrokerConfigRecommendations(clusterId, tab === 'recommended');
  const { canWrite, writeGate, applyGate } = useDeclarationGates(clusterId, declaration.data);
  const [drawer, setDrawer] = useState<Drawer>(null);
  const [scope, setScope] = useState<ApplyScope | null>(null);

  const setSearch = (patch: Partial<ConfigurationSearch>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

  const d = declaration.data;
  if (declaration.isError || !d) {
    return (
      <Page>
        <PageHeader title="Configuration" description={LEAD} />
        {declaration.isError ? (
          <ErrorState error={declaration.error} onRetry={() => void declaration.refetch()} />
        ) : (
          <LoadingState label="Loading the declared configuration" blockSize="24rem" />
        )}
      </Page>
    );
  }
  const studioManaged = d.applyMode === 'STUDIO_MANAGED';
  const nothingDeclared: GateVerdict = d.declared
    ? { kind: 'allowed', uncertain: false }
    : { kind: 'blocked', reason: 'Nothing is declared yet.' };

  const writeButton = (label: string, what: string, onClick: () => void) => (
    <CapabilityGate verdict={writeGate} what={what}>
      <Button variant="default" size="xs" onClick={onClick} disabled={writeGate.kind === 'blocked'}>
        {label}
      </Button>
    </CapabilityGate>
  );

  const panes: Record<NonNullable<ConfigurationSearch['tab']>, ReactNode> = {
    recommended: (
      <RecommendedTab
        clusterId={clusterId}
        query={recommendations}
        onReview={() => setScope({})}
        disabledReason={recommendedReason(d, writeGate)}
      />
    ),
    history: <HistoryTab declaration={d} catalogue={catalogue.data} />,
    declared: (
      <Stack gap="xl">
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
      </Stack>
    ),
  };

  return (
    <Page>
      <PageHeader
        title="Configuration"
        description={LEAD}
        meta={d.declared ? <ModeControl declaration={d} canWrite={canWrite} /> : undefined}
        actions={
          <>
            {writeButton('Adopt from cluster', 'adopting from the cluster', () => setDrawer('adopt'))}
            {writeButton('Import XML', 'importing XML', () => setDrawer('import'))}
            <CapabilityGate verdict={nothingDeclared} what="exporting the declaration">
              <Button
                variant={studioManaged ? 'default' : 'filled'}
                size="xs"
                onClick={() => setDrawer('export')}
                disabled={!d.declared}
              >
                {studioManaged ? 'Export XML' : 'Copy broker.xml fragment'}
              </Button>
            </CapabilityGate>
          </>
        }
      />

      <StatusBar
        declaration={d}
        applyGate={applyGate}
        nothingDeclared={nothingDeclared}
        studioManaged={studioManaged}
        onReview={() => setScope({})}
      />

      {d.declared ? null : <DeclarePrompt declaration={d} writeGate={writeGate} onAdopt={() => setDrawer('adopt')} />}

      <Tabs value={tab} onChange={(next) => setSearch({ tab: (next as ConfigurationSearch['tab']) ?? undefined })}>
        <Tabs.List>
          <Tabs.Tab value="declared">Declared &amp; live</Tabs.Tab>
          <Tabs.Tab value="history">History</Tabs.Tab>
          <Tabs.Tab value="recommended">Recommended</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value={tab} pt="lg">
          {panes[tab]}
        </Tabs.Panel>
      </Tabs>

      <ReviewApplyDrawer declaration={d} scope={scope} opened={scope !== null} onClose={() => setScope(null)} />
      <AdoptDrawer declaration={d} opened={drawer === 'adopt'} onClose={() => setDrawer(null)} />
      <ImportXmlDrawer declaration={d} opened={drawer === 'import'} onClose={() => setDrawer(null)} />
      <ExportXmlDrawer declaration={d} opened={drawer === 'export'} onClose={() => setDrawer(null)} />
    </Page>
  );
}

/** Shown while nothing is declared: what a declaration is, and the ways to make the first one. */
function DeclarePrompt({
  declaration,
  writeGate,
  onAdopt,
}: Readonly<{ declaration: ConfigDeclarationView; writeGate: GateVerdict; onAdopt: () => void }>) {
  return (
    <>
      <AdoptionSuggestion
        declaration={declaration}
        onAdopt={onAdopt}
        canWrite={writeGate.kind !== 'blocked'}
        blockedReason={writeGate.kind === 'blocked' ? writeGate.reason : undefined}
      />
      <EmptyState
        kind="empty"
        title="Declare what this cluster should run"
        description={
          <Stack gap="xs">
            <Text size="sm">
              A declaration is the configuration Studio can apply over the management API and measure every live node
              against: addresses and queues, address settings, security settings, diverts and bridges. Nothing is
              declared for this cluster yet, so there is nothing to compare the nodes with.
            </Text>
            <Text size="sm">
              Start with <b>Adopt from cluster</b> to take what the brokers run today, <b>Import XML</b> to paste a
              broker.xml, or open a section below and add an entry. Static settings — the rest of broker.xml — cannot be
              applied over management and are not part of a declaration.
            </Text>
          </Stack>
        }
      />
    </>
  );
}

/** Why the recommendations cannot be declared from here, when they cannot. */
function recommendedReason(declaration: ConfigDeclarationView, writeGate: GateVerdict): string | undefined {
  if (declaration.applyMode === 'CONFIG_MANAGED') return CONFIG_MANAGED_REASON;
  return writeGate.kind === 'blocked' ? writeGate.reason : undefined;
}

/** "Saved <when> by <who> · <source>" — the revision's provenance. */
function savedLabel(declaration: ConfigDeclarationView): string {
  const by = declaration.updatedBy ? ` by ${declaration.updatedBy}` : '';
  const source = declaration.source ? ` · ${declaration.source.toLowerCase().replaceAll('_', ' ')}` : '';
  return `Saved ${absoluteLabel(declaration.updatedAt)}${by}${source}`;
}

/**
 * Where the declaration has got to, kept on screen (ADR-0087 D1). It carries the
 * one primary action, so a saved revision is never left with no way to reach a
 * broker; when applying is impossible the control stays visible and says why
 * (non-negotiable #5).
 *
 * <p>"Evaluate now" runs one read of every live node. It is busy while it runs, announces that it
 * finished, and when it fails says why and offers the same evaluation again.
 */
function StatusBar({
  declaration,
  applyGate,
  nothingDeclared,
  studioManaged,
  onReview,
}: Readonly<{
  declaration: ConfigDeclarationView;
  applyGate: GateVerdict;
  nothingDeclared: GateVerdict;
  studioManaged: boolean;
  onReview: () => void;
}>) {
  const evaluate = useEvaluateBrokerConfigDrift(declaration.clusterId);
  const applied = appliedWords(declaration);
  const latest = declaration.nodes
    .map((n) => n.evaluatedAt)
    .filter((t): t is string => !!t)
    .sort((a, b) => a.localeCompare(b))
    .at(-1);

  const run = () =>
    evaluate.mutate(undefined, {
      onSuccess: () => notify.succeeded({ action: EVALUATE, subject: 'every live node against the declaration' }),
    });

  return (
    <Stack gap="xs">
      <div className={classes.summaryBar}>
        <Stack gap="xs">
          <div aria-live="polite">
            <StatusBadge tone={applied.tone ?? 'neutral'}>{applied.text}</StatusBadge>
          </div>
          <Text size="sm" c="dimmed">
            {declaration.declared ? `${savedLabel(declaration)} · ` : ''}
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
        <div className={classes.barActions}>
          <CapabilityGate verdict={nothingDeclared} what="evaluating the nodes">
            <Button
              variant="default"
              size="xs"
              loading={evaluate.isPending}
              onClick={run}
              disabled={!declaration.declared}
            >
              Evaluate now
            </Button>
          </CapabilityGate>
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
        </div>
      </div>
      {evaluate.isError ? <ErrorState variant="inline" error={evaluate.error} onRetry={run} /> : null}
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
}: Readonly<{
  clusterId: string;
  query: ReturnType<typeof useBrokerConfigRecommendations>;
  onReview: () => void;
  disabledReason?: string;
}>) {
  if (query.isError) {
    return (
      <Stack gap="sm">
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        <Text size="sm" c="dimmed">
          Studio could not assess this cluster, so it has nothing to recommend — this is not the same as having nothing
          to recommend.
        </Text>
      </Stack>
    );
  }
  if (!query.data) return <LoadingState label="Assessing the cluster" blockSize="16rem" />;
  return (
    <RecommendedConfiguration
      clusterId={clusterId}
      recommendations={query.data}
      onDeclared={onReview}
      disabledReason={disabledReason}
    />
  );
}

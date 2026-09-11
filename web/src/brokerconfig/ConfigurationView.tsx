import { useEffect, useState } from 'react';
import { Alert, Button, Group, Skeleton, Stack, Tabs, Text } from '@mantine/core';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useBrokerConfig, useBrokerConfigCatalogue, useCluster } from '../api/client.ts';
import { absoluteLabel } from '../app/time.ts';
import { useDisplayZone } from '../app/timezone.ts';
import { useCan } from '../auth/useCan.ts';
import { CapabilityGate } from '../shared/CapabilityGate.tsx';
import { gateFor, type GateVerdict } from '../shared/capabilityGate.ts';
import type { ConfigurationSearch } from '../router.tsx';
import { APPLY_PERMISSION_LABEL } from './ApplyView.tsx';
import { DeclaredTab } from './DeclaredTab.tsx';
import { DriftTab } from './DriftTab.tsx';
import { HistoryTab } from './HistoryTab.tsx';
import { ModeControl } from './ModeControl.tsx';
import { AdoptDrawer, ExportXmlDrawer, ImportXmlDrawer } from './XmlDrawers.tsx';
import { CONFIG_MANAGED_REASON, type Section } from './words.ts';

type Drawer = 'adopt' | 'import' | 'export' | null;

export const WRITE_PERMISSION_LABEL = 'Edit declared configuration';

/** The revision note and drawer lead for a snippet handed over from the capability ledger. */
const IMPORT_LABEL: Record<NonNullable<ConfigurationSearch['import']>, string> = {
  notifications: 'Declared from the Live events setup snippet',
  messageIo: 'Declared from the Message browse setup snippet',
  slowConsumerDetection: 'Declared from the Slow-consumer detection setup snippet',
};

/**
 * A cluster's declared configuration (ADR-0067): what it should run, how far
 * each live node is from it, and what has been applied. One primary action per
 * mode — preview and apply, or copy the broker.xml fragment — with the other
 * always visible and disabled with its reason (non-negotiable #5).
 */
export function ConfigurationView() {
  useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as ConfigurationSearch;
  const navigate = useNavigate();
  const tab = search.tab ?? 'declared';

  const declaration = useBrokerConfig(clusterId);
  const catalogue = useBrokerConfigCatalogue(clusterId);
  const cluster = useCluster(clusterId);
  const { can, loading } = useCan();
  const [drawer, setDrawer] = useState<Drawer>(null);

  const setSearch = (patch: Partial<ConfigurationSearch>) =>
    navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });

  // Arrived from the capability ledger with a snippet to declare: open the import
  // drawer on it once the declaration is known, and drop the parameter so a reload
  // or a shared link does not reopen it.
  const importSnippet = search.import ? cluster.data?.capabilities[search.import]?.brokerXmlSnippet : undefined;
  const importLabel = search.import ? IMPORT_LABEL[search.import] : undefined;
  const [handoff, setHandoff] = useState<{ xml: string; note: string } | null>(null);
  useEffect(() => {
    if (!search.import || !declaration.data || !cluster.data) return;
    if (importSnippet) setHandoff({ xml: importSnippet, note: importLabel ?? 'From the capability ledger' });
    setDrawer('import');
    setSearch({ import: undefined });
    // Runs once per hand-off; the search patch above ends it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search.import, declaration.data, cluster.data]);

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
        ? { kind: 'blocked', reason: 'Nothing is declared yet. Adopt from the cluster, import broker.xml or add an entry first.' }
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
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <Stack gap={2}>
          <Text size="sm" fw={600}>
            {d.declared
              ? `Revision ${d.revision} · saved ${absoluteLabel(d.updatedAt)}${d.updatedBy ? ` by ${d.updatedBy}` : ''}${d.source ? ` · ${d.source.toLowerCase().replace(/_/g, ' ')}` : ''}`
              : 'No declaration yet'}
          </Text>
          {d.declared ? <ModeControl declaration={d} canWrite={canWrite} /> : null}
        </Stack>

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
          <CapabilityGate verdict={applyGate}>
            <Button
              component={Link}
              to={`/clusters/${clusterId}/configuration/apply`}
              variant={studioManaged ? 'filled' : 'default'}
              size="xs"
              disabled={applyGate.kind === 'blocked'}
              aria-disabled={applyGate.kind === 'blocked'}
            >
              Preview &amp; apply
            </Button>
          </CapabilityGate>
        </Group>
      </Group>

      {!d.declared ? (
        <Alert variant="light" color="gray" title="Declare what this cluster should run">
          <Stack gap="xs">
            <Text size="sm">
              A declaration is the configuration Studio can apply over the management API and measure every live node
              against: addresses and queues, address settings, security settings and diverts. Nothing is declared for
              this cluster yet, so there is nothing to compare the nodes with.
            </Text>
            <Text size="sm">
              Start with <b>Adopt from cluster</b> to take what the brokers run today, <b>Import XML</b> to paste a
              broker.xml, or open a section below and add an entry. Static settings — the rest of broker.xml — cannot be
              applied over management and are not part of a declaration.
            </Text>
          </Stack>
        </Alert>
      ) : null}

      <Tabs value={tab} onChange={(next) => setSearch({ tab: (next as ConfigurationSearch['tab']) ?? undefined })}>
        <Tabs.List>
          <Tabs.Tab value="declared">Declared</Tabs.Tab>
          <Tabs.Tab value="drift">Drift</Tabs.Tab>
          <Tabs.Tab value="history">History</Tabs.Tab>
        </Tabs.List>
      </Tabs>

      {tab === 'declared' ? (
        <DeclaredTab
          declaration={d}
          catalogue={catalogue.data}
          canWrite={canWrite}
          openSection={search.section as Section | undefined}
          onSectionChange={(section) => setSearch({ section })}
        />
      ) : tab === 'drift' ? (
        <DriftTab declaration={d} canEvaluate={d.declared} catalogue={catalogue.data} />
      ) : (
        <HistoryTab declaration={d} catalogue={catalogue.data} />
      )}

      <AdoptDrawer declaration={d} opened={drawer === 'adopt'} onClose={() => setDrawer(null)} />
      <ImportXmlDrawer
        declaration={d}
        opened={drawer === 'import'}
        onClose={() => {
          setDrawer(null);
          setHandoff(null);
        }}
        initialXml={handoff?.xml}
        initialNote={handoff?.note}
      />
      <ExportXmlDrawer declaration={d} opened={drawer === 'export'} onClose={() => setDrawer(null)} />
    </Stack>
  );
}

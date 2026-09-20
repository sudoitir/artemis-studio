import { useMemo, useState } from 'react';
import { Alert, Grid, Select, Stack, Text } from '@mantine/core';

import type { ConfigDeclarationView } from '../api.ts';
import { AddressEditor } from '../AddressEditor.tsx';
import { DivertEditor } from '../DivertEditor.tsx';
import type { GateVerdict } from '../../../ui/capabilityGate.ts';
import type { Section } from '../words.ts';
import { BridgeEditor, type BridgePrefill } from './BridgeEditor.tsx';
import { RoutingCanvas, type Compose } from './RoutingCanvas.tsx';
import { RoutingInspector } from './RoutingInspector.tsx';
import { anchorCandidates, buildRoutingGraph, GRAPH_BOUND, regionAround } from './routingGraph.ts';

/** What a drag proposed, until its editor is closed. Nothing is written until the document is saved. */
type Proposal =
  | { kind: 'divert'; address: string; forwardingAddress: string }
  | { kind: 'bridge'; prefill: BridgePrefill };

/**
 * The routing builder (ADR-0090 D1): a mode of the one configuration screen, not
 * a screen of its own, so there is one declaration, one plan and one apply.
 *
 * <p>The canvas authors; it never writes to a broker. Saving cuts a revision
 * through the same editors the Declared &amp; live tab opens, and applying is the
 * review drawer on the status bar, untouched.
 */
export function RoutingTab({
  declaration,
  writeGate,
  openSection,
  openItem,
  anchor,
  selected,
  onSearch,
}: {
  declaration: ConfigDeclarationView;
  writeGate: GateVerdict;
  openSection?: Section;
  openItem?: string;
  /** The address a bounded view is drawn around, from the URL. */
  anchor?: string;
  /** The selected element's id, from the URL. */
  selected?: string;
  onSearch: (patch: { section?: Section; item?: string; anchor?: string; selected?: string }) => void;
}) {
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const canWrite = writeGate.kind !== 'blocked';

  const whole = useMemo(() => buildRoutingGraph(declaration), [declaration]);
  const anchors = useMemo(() => anchorCandidates(whole), [whole]);
  const anchorId = anchor ? `address:${anchor}` : (anchors[0]?.id ?? '');
  const bounded = useMemo(() => regionAround(whole, anchorId), [whole, anchorId]);
  const graph = bounded.graph;

  const selectedNode = graph.nodes.find((n) => n.id === selected) ?? null;

  const compose = (c: Compose) => {
    if (c.kind === 'divert') {
      setProposal({ kind: 'divert', address: c.address, forwardingAddress: c.forwardingAddress });
      onSearch({ section: 'diverts', item: undefined });
    } else {
      setProposal({ kind: 'bridge', prefill: { queueName: c.queueName, forwardingAddress: c.forwardingAddress } });
      onSearch({ section: 'bridges', item: undefined });
    }
  };

  const closeEditor = () => {
    setProposal(null);
    onSearch({ section: undefined, item: undefined });
  };

  if (whole.nodes.length === 0) {
    return (
      <Alert variant="light" color="gray" title="Nothing to draw yet">
        <Text size="sm">
          This declaration has no addresses, diverts or bridges, and no node has reported one that is not declared. Add
          an address on the Declared &amp; live tab, or adopt what the cluster runs, and the graph draws what you
          declare.
        </Text>
      </Alert>
    );
  }

  return (
    <Stack gap="sm">
      {bounded.hidden > 0 ? (
        <Alert variant="light" color="gray" title="Showing a bounded region of the graph">
          <Stack gap="xs">
            <Text size="sm">
              This cluster's routing has {whole.nodes.length} elements, more than the {GRAPH_BOUND} this canvas draws at
              once. It is showing the {graph.nodes.length} reachable from{' '}
              {anchors.find((a) => a.id === anchorId)?.name ?? 'the first address'}; {bounded.hidden} elements are not
              drawn. Every one of them is on the Declared &amp; live tab.
            </Text>
            <Select
              label="Draw the region around"
              size="xs"
              searchable
              data={anchors.map((a) => ({ value: a.name, label: a.name }))}
              value={anchors.find((a) => a.id === anchorId)?.name ?? null}
              onChange={(v) => onSearch({ anchor: v ?? undefined, selected: undefined })}
              allowDeselect={false}
              w={320}
            />
          </Stack>
        </Alert>
      ) : null}

      <Grid>
        <Grid.Col span={{ base: 12, md: 8 }}>
          <RoutingCanvas
            graph={graph}
            selectedId={selected ?? null}
            onSelect={(id) => onSearch({ selected: id ?? undefined })}
            onCompose={compose}
            canWrite={canWrite}
          />
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }}>
          <RoutingInspector
            declaration={declaration}
            node={selectedNode}
            writeGate={writeGate}
            onEdit={(section, item) => onSearch({ section: section as Section, item })}
          />
        </Grid.Col>
      </Grid>

      <DivertEditor
        declaration={declaration}
        item={openItem ? (declaration.document.diverts.find((d) => d.name === openItem) ?? null) : null}
        prefill={proposal?.kind === 'divert' ? { address: proposal.address, forwardingAddress: proposal.forwardingAddress } : undefined}
        opened={openSection === 'diverts'}
        onClose={closeEditor}
      />
      <BridgeEditor
        declaration={declaration}
        item={openItem ? (declaration.document.bridges.find((b) => b.name === openItem) ?? null) : null}
        prefill={proposal?.kind === 'bridge' ? proposal.prefill : undefined}
        opened={openSection === 'bridges'}
        onClose={closeEditor}
      />
      {/* An address or a queue on the canvas edits the address that declares it,
          in the same drawer the Declared & live tab opens. */}
      <AddressEditor
        declaration={declaration}
        item={openItem ? (declaration.document.addresses.find((a) => a.name === openItem) ?? null) : null}
        opened={openSection === 'addresses'}
        onClose={closeEditor}
      />
    </Stack>
  );
}

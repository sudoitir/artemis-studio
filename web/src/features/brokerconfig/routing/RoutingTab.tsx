import { useMemo, useState } from 'react';
import { Alert, Button, Select, Stack, Text } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';

import type { ConfigAddressView, ConfigDeclarationView } from '../api.ts';
import { AddressEditor } from '../AddressEditor.tsx';
import { DivertEditor } from '../DivertEditor.tsx';
import { CapabilityGate } from '../../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../../ui/capabilityGate.ts';
import type { Section } from '../words.ts';
import { BridgeEditor, type BridgePrefill } from './BridgeEditor.tsx';
import { RoutingCanvas, type Compose } from './RoutingCanvas.tsx';
import { RoutingInspector } from './RoutingInspector.tsx';
import { anchorCandidates, buildRoutingGraph, GRAPH_BOUND, regionAround } from './routingGraph.ts';
import classes from './RoutingCanvas.module.css';

/** What a drag proposed, until its editor is closed. Nothing is written until the document is saved. */
type Proposal =
  | { kind: 'divert'; address: string; forwardingAddress: string }
  | { kind: 'bridge'; prefill: BridgePrefill }
  | { kind: 'queue' };

/** "Add queue" opens a new address with one queue row to name. */
const NEW_QUEUE: Partial<ConfigAddressView> = {
  routingTypes: ['ANYCAST'],
  queues: [{ name: '', routingType: 'ANYCAST', durable: true }],
};

/** Live nodes that do not yet run this revision: what an apply would still have to reach. */
function nodesBehind(declaration: ConfigDeclarationView): number {
  return declaration.nodes.filter(
    (n) => n.live && !(n.state === 'IN_SYNC' && n.verifiedRevision === declaration.revision),
  ).length;
}

/**
 * The routing builder (ADR-0090 D1, ADR-0094): the Routing screen's Builder tab, editing the
 * one declaration, so there is one plan and one apply.
 *
 * <p>The canvas authors; it never writes to a broker. Saving cuts a revision through the same
 * editors the Configuration screen opens, and applying is the same review drawer, reached from
 * the toolbar.
 */
export function RoutingTab({
  declaration,
  writeGate,
  applyGate,
  onReview,
  openSection,
  openItem,
  anchor,
  selected,
  onSearch,
}: {
  declaration: ConfigDeclarationView;
  writeGate: GateVerdict;
  applyGate: GateVerdict;
  /** Open the review-and-apply drawer on the whole declaration. */
  onReview: () => void;
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
  const behind = nodesBehind(declaration);

  const compose = (c: Compose) => {
    if (c.kind === 'divert') {
      setProposal({ kind: 'divert', address: c.address, forwardingAddress: c.forwardingAddress });
      onSearch({ section: 'diverts', item: undefined });
    } else {
      setProposal({ kind: 'bridge', prefill: { queueName: c.queueName, forwardingAddress: c.forwardingAddress } });
      onSearch({ section: 'bridges', item: undefined });
    }
  };

  const addQueue = () => {
    setProposal({ kind: 'queue' });
    onSearch({ section: 'addresses', item: undefined });
  };

  const closeEditor = () => {
    setProposal(null);
    onSearch({ section: undefined, item: undefined });
  };

  const addQueueButton = (
    <CapabilityGate verdict={writeGate} what="adding a queue">
      <Button
        variant="default"
        size="xs"
        leftSection={<IconPlus size={14} stroke={1.75} />}
        onClick={addQueue}
        disabled={!canWrite}
      >
        Add queue
      </Button>
    </CapabilityGate>
  );

  const reviewButton = (
    <CapabilityGate verdict={applyGate} what="review and apply">
      <Button
        size="xs"
        variant={behind > 0 ? 'filled' : 'default'}
        onClick={onReview}
        disabled={applyGate.kind === 'blocked'}
        rightSection={
          behind > 0 ? (
            <span className={classes.count}>
              {behind} node{behind === 1 ? '' : 's'} behind
            </span>
          ) : undefined
        }
      >
        Review &amp; apply
      </Button>
    </CapabilityGate>
  );

  const editors = (
    <>
      <DivertEditor
        declaration={declaration}
        item={openItem ? (declaration.document.diverts.find((d) => d.name === openItem) ?? null) : null}
        prefill={proposal?.kind === 'divert' ? proposal : undefined}
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
      {/* An address or a queue on the canvas edits the address that declares it, in the same
          drawer the Configuration screen opens; "Add queue" opens it new, with a queue to name. */}
      <AddressEditor
        declaration={declaration}
        item={openItem ? (declaration.document.addresses.find((a) => a.name === openItem) ?? null) : null}
        prefill={proposal?.kind === 'queue' ? NEW_QUEUE : undefined}
        opened={openSection === 'addresses'}
        onClose={closeEditor}
      />
    </>
  );

  if (whole.nodes.length === 0) {
    return (
      <>
        <Alert variant="light" color="gray" title="Nothing to draw yet">
          <Stack gap="xs" align="flex-start">
            <Text size="sm">
              The builder draws this cluster's declared routing — addresses, their queues, and the diverts and bridges
              between them — beside what the brokers report. Nothing is declared yet, and no node has reported
              anything that is not. Add a queue to start, or adopt what the cluster runs from the Configuration screen.
            </Text>
            <div>{addQueueButton}</div>
          </Stack>
        </Alert>
        {editors}
      </>
    );
  }

  const anchorName = anchors.find((a) => a.id === anchorId)?.name ?? null;

  return (
    <Stack gap="sm">
      {bounded.hidden > 0 ? (
        <Alert variant="light" color="gray" title="Showing a bounded region of the graph">
          <Text size="sm">
            This cluster's routing has {whole.nodes.length} elements, more than the {GRAPH_BOUND} this canvas draws at
            once. It is showing the {graph.nodes.length} reachable from {anchorName ?? 'the first address'};{' '}
            {bounded.hidden} elements are not drawn. Every one of them is on the Configuration screen's Declared &amp;
            live tab. Choose another address to draw the region around in the toolbar.
          </Text>
        </Alert>
      ) : null}

      <div className={classes.builder}>
        <RoutingCanvas
          graph={graph}
          selectedId={selected ?? null}
          onSelect={(id) => onSearch({ selected: id ?? undefined })}
          onCompose={compose}
          canWrite={canWrite}
          leading={
            bounded.hidden > 0 ? (
              <Select
                label="Draw the region around"
                size="xs"
                searchable
                data={anchors.map((a) => ({ value: a.name, label: a.name }))}
                value={anchorName}
                onChange={(v) => onSearch({ anchor: v ?? undefined, selected: undefined })}
                allowDeselect={false}
                classNames={{ root: classes.inlineField, label: classes.inlineLabel, wrapper: classes.inlineInput }}
                w={340}
              />
            ) : null
          }
          actions={
            <>
              {addQueueButton}
              {reviewButton}
            </>
          }
        />
        <aside className={classes.inspector} aria-label="Selected element">
          <RoutingInspector
            declaration={declaration}
            node={selectedNode}
            writeGate={writeGate}
            onEdit={(section, item) => onSearch({ section: section as Section, item })}
          />
        </aside>
      </div>

      {editors}
    </Stack>
  );
}

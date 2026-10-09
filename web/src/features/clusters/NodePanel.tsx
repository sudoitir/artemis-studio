import { Button, Text } from '@mantine/core';

import { Ago } from '../../kernel/time/Ago.tsx';
import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { OutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { LogicalNodeView } from './api.ts';
import { lastSeenWords, nodeFacts, pairVerdict, type NodeFacts } from './nodeFacts.ts';
import canvas from './TopologyCanvas.module.css';

const NOT_REPORTED = 'Not reported';

function items(facts: NodeFacts, now: number): DescriptionItem[] {
  const seen = lastSeenWords(facts.lastSeenAt, now);
  return [
    { term: 'Role', value: facts.role },
    {
      term: 'Liveness',
      // Colour only where something is wrong: a node that is as it should be is plain words.
      value: facts.liveness.tone ? (
        <StatusBadge tone={facts.liveness.tone}>{facts.liveness.text}</StatusBadge>
      ) : (
        facts.liveness.text
      ),
    },
    { term: 'Pair', value: facts.pair },
    { term: 'Version', value: facts.version ?? 'Unknown', hint: facts.versionNote },
    { term: 'Node ID', value: facts.artemisNodeId ?? NOT_REPORTED },
    { term: 'Management URL', value: facts.jolokiaUrl ?? 'None set' },
    { term: 'Core URL', value: facts.coreUrl ?? 'None set' },
    {
      term: 'Last seen',
      value: facts.lastSeenAt ? <Ago at={facts.lastSeenAt} now={now} /> : seen.relative,
      hint: facts.lastSeenAt ? seen.absolute : undefined,
    },
    { term: 'Last error', value: facts.lastError ?? 'None' },
    { term: 'How found', value: facts.origin },
  ];
}

/** What the side panel says before a node is chosen: how to choose one, so the canvas never changes width. */
export function NodePanelPlaceholder() {
  return (
    <Section title="Node details" variant="card">
      <Text size="sm" c="dimmed">
        Choose a node, in the graph or in the table, to read its role, liveness, pair and version here, and how Studio
        found it.
      </Text>
    </Section>
  );
}

/** What the side panel says when the address names a node the cluster no longer has. */
export function NodePanelMissing({ onClear }: Readonly<{ onClear: () => void }>) {
  return (
    <Section title="Node details" variant="card">
      <Notice
        title="Node not found"
        action={
          <Button size="xs" variant="default" onClick={onClear}>
            Clear selection
          </Button>
        }
      >
        This cluster has no node with that id. It may have been removed, or the link may be from another cluster.
      </Notice>
    </Section>
  );
}

/**
 * One chosen node: its facts as a list, and the state of its pair per node, so what one endpoint
 * says can be read against the other. A node found but not yet managed carries the way to fix that,
 * which used to sit inside its box.
 */
export function NodePanel({
  facts,
  logical,
  now,
  onAddManagementUrl,
}: Readonly<{
  facts: NodeFacts;
  logical: LogicalNodeView;
  now: number;
  /** Opens the add-a-management-URL flow for this node. */
  onAddManagementUrl: () => void;
}>) {
  const { verdict, tone } = pairVerdict(logical);
  return (
    <Section
      title={facts.name}
      variant="card"
      // The node's state beside its name, with the mark its box draws, so the panel opens on the answer.
      actions={
        <span className={canvas.state}>
          <span className={canvas.mark} data-kind={facts.mark} aria-hidden="true" />
          <StatusBadge tone={facts.liveness.tone}>{facts.liveness.label}</StatusBadge>
        </span>
      }
    >
      <DescriptionList items={items(facts, now)} label="Node facts" />
      {facts.manageable ? null : (
        <div>
          <Button size="xs" variant="default" onClick={onAddManagementUrl}>
            Add a management URL
          </Button>
        </div>
      )}
      <Section title="Its pair" headingLevel={3}>
        <OutcomeSummary
          verdict={verdict}
          verdictTone={tone}
          total={`${logical.endpoints.length} ${logical.endpoints.length === 1 ? 'node' : 'nodes'}`}
          rows={logical.endpoints.map((e) => {
            const f = nodeFacts(e, logical);
            return {
              key: e.id,
              name: f.name,
              status: f.liveness.label,
              tone: f.liveness.tone,
              detail: f.lastError,
            };
          })}
        />
      </Section>
    </Section>
  );
}

import { Button, Group, Stack, Text } from '@mantine/core';

import type { ConfigDeclarationView } from '../api.ts';
import { removeItem } from '../document.ts';
import { KeyValueList } from '../KeyValueList.tsx';
import { addressRows, bridgeRows, divertRows, type Row } from '../pretty.ts';
import { useSaveDocument } from '../useSaveDocument.ts';
import { CapabilityGate } from '../../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../../ui/capabilityGate.ts';
import { KIND_WORDS, STATE_WORDS, type RoutingNodeView } from './routingGraph.ts';
import classes from '../Configuration.module.css';

/** What the declaration says about the selected element, or why there is nothing to say. */
function rowsFor(declaration: ConfigDeclarationView, node: RoutingNodeView): Row[] {
  const doc = declaration.document;
  switch (node.kind) {
    case 'address': {
      const a = doc.addresses.find((x) => x.name === node.name);
      return a ? addressRows(a) : [];
    }
    case 'queue': {
      const a = doc.addresses.find((x) => x.queues.some((q) => q.name === node.name));
      return a ? addressRows(a).filter((r) => r.key === `queue ${node.name}`) : [];
    }
    case 'divert': {
      const d = doc.diverts.find((x) => x.name === node.name);
      return d ? divertRows(d) : [];
    }
    case 'bridge': {
      const b = doc.bridges.find((x) => x.name === node.name);
      return b ? bridgeRows(b) : [];
    }
    default:
      return [];
  }
}

/**
 * The selected element: what it is, what it connects, which of the declared and
 * observed states it is in, and the actions that change it. Its editor is the
 * same drawer the Declared &amp; live tab opens, so the two presentations cannot
 * drift apart (ADR-0090).
 */
export function RoutingInspector({
  declaration,
  node,
  writeGate,
  onEdit,
}: {
  declaration: ConfigDeclarationView;
  node: RoutingNodeView | null;
  writeGate: GateVerdict;
  onEdit: (section: string, item: string) => void;
}) {
  const { save, isPending } = useSaveDocument(declaration, () => {});

  if (!node) {
    return (
      <Stack gap={4}>
        <Text size="sm" fw={600}>
          Nothing selected
        </Text>
        <Text size="xs" c="dimmed">
          Choose an element on the canvas, or enter the graph and move with the arrow keys. Everything it shows is also
          on the Declared &amp; live tab, with the same editors.
        </Text>
      </Stack>
    );
  }

  const rows = rowsFor(declaration, node);
  const attention = node.state === 'DECLARED_ONLY' || node.state === 'OBSERVED_ONLY' || node.fault !== null;
  const remove =
    node.edit && node.kind === 'divert'
      ? () => save(removeItem(declaration.document, 'diverts', node.name), `Removed divert ${node.name}`)
      : node.edit && node.kind === 'bridge'
        ? () => save(removeItem(declaration.document, 'bridges', node.name), `Removed bridge ${node.name}`)
        : null;

  return (
    <Stack gap="xs">
      <Stack gap={2}>
        <Text size="xs" c="dimmed">
          {KIND_WORDS[node.kind]}
        </Text>
        <Text size="sm" fw={600}>
          {node.name}
        </Text>
        <Text size="xs" c="dimmed">
          {node.connects}.
        </Text>
        <Text size="xs" className={classes.state} data-tone={attention ? 'warning' : undefined} aria-live="polite">
          {STATE_WORDS[node.state]}
          {node.fault ? `; ${node.fault}` : ''}
        </Text>
        {node.state === 'OBSERVED_ONLY' ? (
          <Text size="xs" c="dimmed">
            That is a statement about this declaration, not about where the element came from. Artemis records nothing
            that would say.
          </Text>
        ) : null}
      </Stack>

      {rows.length > 0 ? <KeyValueList rows={rows} limit={12} /> : null}

      {node.edit ? (
        <Group gap="xs">
          <CapabilityGate verdict={writeGate} what={`editing ${node.kind} ${node.name}`}>
            <Button
              variant="default"
              size="xs"
              onClick={() => onEdit(node.edit!.section, node.edit!.item)}
              disabled={writeGate.kind === 'blocked'}
            >
              Edit {node.kind} {node.name}
            </Button>
          </CapabilityGate>
          {remove ? (
            <CapabilityGate verdict={writeGate} what={`removing ${node.kind} ${node.name}`}>
              <Button
                variant="subtle"
                color="red"
                size="xs"
                loading={isPending}
                onClick={remove}
                disabled={writeGate.kind === 'blocked'}
              >
                Remove from declaration
              </Button>
            </CapabilityGate>
          ) : null}
        </Group>
      ) : (
        <Text size="xs" c="dimmed">
          {node.kind === 'target'
            ? 'This is on another broker. Declare the bridge that reaches it, not the target itself.'
            : 'Studio does not declare this element, so there is nothing here to edit. Declare it to bring it under the declaration.'}
        </Text>
      )}
    </Stack>
  );
}

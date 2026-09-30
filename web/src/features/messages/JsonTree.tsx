import { useMemo, useState } from 'react';
import {
  Button,
  Code,
  CopyButton,
  getTreeExpandedState,
  Group,
  Stack,
  Text,
  TextInput,
  Tree,
  useTree,
  type RenderTreeNodePayload,
  type TreeNodeData,
} from '@mantine/core';
import { IconChevronRight } from '@tabler/icons-react';

import { sqlBodyPath } from './payload.ts';

/** What a node stands for: its path from the root, and a one-line rendering of its value. */
interface NodeInfo {
  segments: (string | number)[];
  key: string;
  summary: string;
  primitive: boolean;
}

function summary(value: unknown): string {
  if (Array.isArray(value)) return `[${value.length}]`;
  if (value !== null && typeof value === 'object') return `{${Object.keys(value).length}}`;
  return JSON.stringify(value);
}

/** One tree node per member, keyed by its path so every value is unique. */
function toNodes(value: unknown, segments: (string | number)[], info: Map<string, NodeInfo>): TreeNodeData[] {
  const entries: [string | number, unknown][] = Array.isArray(value)
    ? value.map((v, i) => [i, v])
    : Object.entries(value as Record<string, unknown>);
  return entries.map(([key, child]) => {
    const path = [...segments, key];
    const id = JSON.stringify(path);
    const primitive = child === null || typeof child !== 'object';
    info.set(id, { segments: path, key: String(key), summary: summary(child), primitive });
    return { value: id, label: String(key), children: primitive ? undefined : toNodes(child, path, info) };
  });
}

/** Nodes whose key or value contains the query, with the ancestors that lead to them. */
function filterNodes(nodes: TreeNodeData[], query: string, info: Map<string, NodeInfo>): TreeNodeData[] {
  return nodes.flatMap((node) => {
    const own = info.get(node.value)!;
    const hit = own.key.toLowerCase().includes(query) || (own.primitive && own.summary.toLowerCase().includes(query));
    const children = node.children ? filterNodes(node.children, query, info) : undefined;
    if (hit) return [node];
    return children && children.length > 0 ? [{ ...node, children }] : [];
  });
}

/**
 * A parsed JSON body as a collapsible tree. Selecting a node shows the path the SQL console takes for it,
 * so a field seen here is one copy away from a query. Keys and values are text, never markup.
 */
export function JsonTree({ value }: Readonly<{ value: unknown }>) {
  const [query, setQuery] = useState('');
  const { data, info } = useMemo(() => {
    const map = new Map<string, NodeInfo>();
    const root = value !== null && typeof value === 'object' ? toNodes(value, [], map) : [];
    return { data: root, info: map };
  }, [value]);

  const needle = query.trim().toLowerCase();
  const shown = useMemo(() => (needle ? filterNodes(data, needle, info) : data), [data, needle, info]);
  // While filtering, every match is shown open; clearing the filter restores what the operator opened.
  const [opened, setOpened] = useState(() => getTreeExpandedState(data, []));
  const tree = useTree({
    expandedState: needle ? getTreeExpandedState(shown, '*') : opened,
    onExpandedStateChange: needle ? undefined : setOpened,
  });
  const [selected] = tree.selectedState;
  const picked = selected ? info.get(selected) : undefined;

  return (
    <Stack gap="xs">
      <TextInput
        size="xs"
        label="Filter keys and values"
        value={query}
        onChange={(e) => setQuery(e.currentTarget.value)}
      />
      <PathBar picked={picked} />
      {shown.length === 0 ? (
        <Text size="xs" c="dimmed">
          {needle ? `No key or value contains “${query.trim()}”.` : 'The body is an empty object or array.'}
        </Text>
      ) : (
        <Tree
          data={shown}
          tree={tree}
          selectOnClick
          aria-label="Message body"
          renderNode={(payload) => <JsonNode payload={payload} info={info} />}
        />
      )}
    </Stack>
  );
}

function JsonNode({ payload, info }: Readonly<{ payload: RenderTreeNodePayload; info: Map<string, NodeInfo> }>) {
  const { node, expanded, hasChildren, elementProps } = payload;
  const own = info.get(node.value)!;
  return (
    <Group gap={4} wrap="nowrap" {...elementProps}>
      <IconChevronRight
        size={12}
        aria-hidden
        style={{
          visibility: hasChildren ? 'visible' : 'hidden',
          transform: expanded ? 'rotate(90deg)' : undefined,
        }}
      />
      <Text size="xs" ff="monospace" fw={600}>
        {own.key}
      </Text>
      <Text size="xs" ff="monospace" c="dimmed" truncate>
        {own.summary}
      </Text>
    </Group>
  );
}

/** The selected field's SQL console path, or why the console cannot name it. */
function PathBar({ picked }: Readonly<{ picked: NodeInfo | undefined }>) {
  if (!picked) {
    return (
      <Text size="xs" c="dimmed">
        Select a field to see its path for the SQL console.
      </Text>
    );
  }
  const path = sqlBodyPath(picked.segments);
  if (!path) {
    return (
      <Text size="xs" c="dimmed">
        A key on this path contains a dot or a quote, which a SQL console path cannot name.
      </Text>
    );
  }
  return (
    <Group gap="xs" wrap="nowrap">
      <Code>{path}</Code>
      <CopyButton value={path}>
        {({ copied, copy }) => (
          <Button size="compact-xs" variant="default" onClick={copy}>
            {copied ? 'Copied path' : 'Copy path'}
          </Button>
        )}
      </CopyButton>
    </Group>
  );
}

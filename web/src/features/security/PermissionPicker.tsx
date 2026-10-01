import { useMemo, useState, type ReactNode } from 'react';
import {
  Accordion,
  Button,
  Center,
  Checkbox,
  CloseButton,
  Group,
  Stack,
  Text,
  TextInput,
  VisuallyHidden,
} from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { PermissionView } from './api.ts';
import classes from './Security.module.css';

const UNCATALOGUED = '__uncatalogued__';
const WILDCARDS = '__wildcards__';

type Entry = { action: string; label: string; globalOnly: boolean };
type PermissionGroup = { id: string; title: string; entries: Entry[] };

/** Groups the catalogue by owning module or plugin, plus the held permissions the catalogue lacks. */
function groupPermissions(catalogue: PermissionView[], selected: string[]): PermissionGroup[] {
  const groups = new Map<string, PermissionGroup>();
  for (const p of catalogue) {
    const group = groups.get(p.featureId) ?? { id: p.featureId, title: p.featureTitle, entries: [] };
    group.entries.push({ action: p.action, label: p.label, globalOnly: p.globalOnly });
    groups.set(p.featureId, group);
  }
  const known = new Set(catalogue.map((p) => p.action));
  const isWildcard = (a: string) => a === '*' || a.endsWith(':*');
  const wildcards = selected.filter((a) => isWildcard(a) && !known.has(a));
  const missing = selected.filter((a) => !isWildcard(a) && !known.has(a));
  const result = [...groups.values()];
  if (wildcards.length > 0) {
    result.push({
      id: WILDCARDS,
      title: 'Wildcards',
      entries: wildcards.map((action) => ({
        action,
        label:
          action === '*'
            ? 'Grants every permission, including those of modules and plugins added later.'
            : `Grants every ${action.slice(0, -1)} permission, including ones added later.`,
        globalOnly: false,
      })),
    });
  }
  if (missing.length > 0) {
    result.push({
      id: UNCATALOGUED,
      title: 'Not in the catalogue',
      entries: missing.map((action) => ({
        action,
        label: 'Its module or plugin is not active. The role keeps it unless you clear it.',
        globalOnly: false,
      })),
    });
  }
  return result;
}

function matches(entry: Entry, query: string): boolean {
  const q = query.trim().toLowerCase();
  return q === '' || entry.action.toLowerCase().includes(q) || entry.label.toLowerCase().includes(q);
}

/** What stands in for the groups when no permission is declared, or none matches the search. */
function pickerNotice(groupCount: number, visibleCount: number, query: string, clear: () => void): ReactNode {
  if (groupCount === 0) {
    return <Text size="sm">No module or plugin declares a permission, so there is nothing to grant.</Text>;
  }
  if (visibleCount > 0) return null;
  return (
    <Stack gap={4} align="flex-start">
      <Text size="sm">No permission matches “{query}”.</Text>
      <Button size="xs" variant="subtle" onClick={clear}>
        Clear search
      </Button>
    </Stack>
  );
}

/**
 * The role editor's permission picker (operator-ui spec): grouped by module or plugin, searchable,
 * with each permission's description and whether it acts only at global scope, and select-all or
 * clear per group, announced.
 */
export function PermissionPicker({
  catalogue,
  value,
  onChange,
}: Readonly<{
  catalogue: PermissionView[];
  value: string[];
  onChange: (next: string[]) => void;
}>) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState<string[]>([]);
  const [announcement, setAnnouncement] = useState('');

  const groups = useMemo(() => groupPermissions(catalogue, value), [catalogue, value]);
  const visible = groups
    .map((g) => ({ ...g, entries: g.entries.filter((e) => matches(e, query)) }))
    .filter((g) => g.entries.length > 0);
  const selected = new Set(value);

  function toggle(action: string) {
    onChange(selected.has(action) ? value.filter((a) => a !== action) : [...value, action]);
  }

  function setGroup(group: PermissionGroup, on: boolean) {
    const actions = group.entries.map((e) => e.action);
    const next = on
      ? [...value, ...actions.filter((a) => !selected.has(a))]
      : value.filter((a) => !actions.includes(a));
    onChange(next);
    const held = actions.filter((a) => next.includes(a)).length;
    setAnnouncement(`${held} of ${actions.length} permissions selected in ${group.title}`);
  }

  return (
    <Stack gap="xs">
      <div className={classes.search}>
        <TextInput
          className={classes.searchField}
          label="Search permissions"
          leftSection={<IconSearch size="1rem" aria-hidden />}
          value={query}
          onChange={(e) => setQuery(e.currentTarget.value)}
          rightSection={query ? <CloseButton aria-label="Clear search" onClick={() => setQuery('')} size="sm" /> : null}
        />
        <Text size="sm" c="dimmed" className={classes.figure}>
          {value.length} selected
        </Text>
      </div>

      <VisuallyHidden role="status" aria-live="polite">
        {announcement}
      </VisuallyHidden>

      {pickerNotice(groups.length, visible.length, query, () => setQuery('')) ?? (
        <Accordion
          multiple
          chevronPosition="left"
          value={query.trim() ? visible.map((g) => g.id) : open}
          onChange={setOpen}
        >
          {visible.map((group) => {
            const all = groups.find((g) => g.id === group.id)!.entries;
            const held = all.filter((e) => selected.has(e.action)).length;
            return (
              <Accordion.Item key={group.id} value={group.id}>
                <Center>
                  <Checkbox
                    aria-label={`Select all in ${group.title}`}
                    checked={held === all.length}
                    indeterminate={held > 0 && held < all.length}
                    onChange={() => setGroup({ ...group, entries: all }, held < all.length)}
                    ms="sm"
                  />
                  <Accordion.Control aria-label={`${group.title}, ${held} of ${all.length} selected`}>
                    <Group justify="space-between" wrap="nowrap">
                      <Text size="sm" fw={500}>
                        {group.title}
                      </Text>
                      <Text size="sm" c="dimmed" className={classes.figure}>
                        {held}/{all.length}
                      </Text>
                    </Group>
                  </Accordion.Control>
                </Center>
                <Accordion.Panel>
                  <Stack gap="xs">
                    {group.entries.map((e) => (
                      <Checkbox
                        key={e.action}
                        label={
                          <Group gap={6} wrap="nowrap">
                            <Text size="sm" ff="monospace">
                              {e.action}
                            </Text>
                            {e.globalOnly ? <StatusBadge>Global only</StatusBadge> : null}
                          </Group>
                        }
                        description={
                          e.globalOnly
                            ? `${e.label}. Has no effect when granted on an environment or cluster.`
                            : e.label
                        }
                        checked={selected.has(e.action)}
                        onChange={() => toggle(e.action)}
                      />
                    ))}
                  </Stack>
                </Accordion.Panel>
              </Accordion.Item>
            );
          })}
        </Accordion>
      )}
    </Stack>
  );
}

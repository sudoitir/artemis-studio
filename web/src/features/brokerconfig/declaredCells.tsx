import { Button, Group, Stack, Text } from '@mantine/core';

import type { ConfigCatalogueView, ConfigDeclarationView } from './api.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { asItems, type Row } from './pretty.ts';
import type { ApplyScope } from './ReviewApplyDrawer.tsx';
import { findingRows, itemDriftWords, itemFindings, type Section } from './words.ts';
import classes from './Configuration.module.css';

/** What a section's columns need from the screen: the declaration, the gates and the two actions. */
export interface DeclaredContext {
  declaration: ConfigDeclarationView;
  catalogue: ConfigCatalogueView | undefined;
  canWrite: boolean;
  /** Whether an apply is possible at all, and why not. */
  applyGate: GateVerdict;
  onEdit: (section: Section, item?: string) => void;
  onApply: (scope: ApplyScope) => void;
}

/** How one declared section reads as a table: what names an item, what it declares, what it applies. */
export interface DeclaredSpec<T> {
  section: Section;
  /** "address", "divert": names the item in the actions' labels. */
  noun: string;
  nameHeader: string;
  valuesHeader: string;
  nameOf: (item: T) => string;
  rowsOf: (item: T) => Row[];
  /** What the declared cell says when the item declares no keys. */
  noRows?: string;
  /** The queues declared on an address: their findings carry their own name, not the row's. */
  queueKeysOf?: (item: T) => string[];
  /** The apply scope of the item: the plan steps that belong to it. */
  scopeOf: (item: T) => ApplyScope;
}

/**
 * One declared item's live state (ADR-0087 D1): the sentence first, in words, with the nodes named,
 * and underneath it the keys that actually differ, declared → observed.
 */
export function LiveState<T>({ ctx, spec, item }: Readonly<{ ctx: DeclaredContext; spec: DeclaredSpec<T>; item: T }>) {
  const { declaration, catalogue } = ctx;
  const key = spec.nameOf(item);
  const queueKeys = spec.queueKeysOf?.(item);
  const { text, tone } = itemDriftWords(declaration, spec.section, key, queueKeys);
  const found = itemFindings(declaration, spec.section, key, queueKeys);
  return (
    <Stack gap="xs">
      <div>
        <StatusBadge tone={tone ?? 'neutral'}>{text}</StatusBadge>
      </div>
      {found.map(({ nodeName, label, finding }, i) => {
        // A missing item differs in every key, and "declared → —" repeated down the whole entry says
        // nothing the sentence above has not already said. Only a divergence earns its keys.
        if (finding.kind === 'MISSING') return null;
        const differing = findingRows(finding, catalogue).filter((r) => r.differs);
        if (differing.length === 0) return null;
        return (
          <DescriptionList
            key={`${nodeName}:${i}`}
            label={`${nodeName} differs`}
            items={differing.map((r) => ({
              term: `${label}${r.key}`,
              value: (
                <>
                  <span className={classes.before}>{r.declared}</span>
                  {' → '}
                  {r.observed}
                </>
              ),
            }))}
          />
        );
      })}
    </Stack>
  );
}

export function DeclaredValues({ rows, empty }: Readonly<{ rows: Row[]; empty?: string }>) {
  if (rows.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {empty ?? '—'}
      </Text>
    );
  }
  return <DescriptionList items={asItems(rows)} />;
}

/** Edit (or View, without the permission) and Apply this, each named for the item it acts on. */
export function ItemActions<T>({
  ctx,
  spec,
  item,
}: Readonly<{ ctx: DeclaredContext; spec: DeclaredSpec<T>; item: T }>) {
  const { canWrite, applyGate, onEdit, onApply } = ctx;
  const key = spec.nameOf(item);
  const name = `${spec.noun} ${key}`;
  return (
    <Group gap="xs" justify="flex-end" wrap="wrap">
      <Button
        variant="subtle"
        size="compact-sm"
        onClick={() => onEdit(spec.section, key)}
        aria-label={`${canWrite ? 'Edit' : 'View'} ${name}`}
      >
        {canWrite ? 'Edit' : 'View'}
      </Button>
      <CapabilityGate verdict={applyGate} what={`applying ${name}`}>
        <Button
          variant="subtle"
          size="compact-sm"
          onClick={() => onApply(spec.scopeOf(item))}
          disabled={applyGate.kind === 'blocked'}
          aria-label={`Apply ${name}`}
        >
          Apply this
        </Button>
      </CapabilityGate>
    </Group>
  );
}

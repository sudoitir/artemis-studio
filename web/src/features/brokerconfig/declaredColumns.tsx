import type { Column } from '../../ui/table/index.ts';
import { DeclaredValues, ItemActions, LiveState, type DeclaredContext, type DeclaredSpec } from './declaredCells.tsx';
import { itemDriftWords } from './words.ts';

export type { DeclaredContext, DeclaredSpec };

/**
 * A declared section's columns: the item, what it declares, how the live nodes compare, and the
 * actions that change either. Every section reads the same way, so the screen's one job, the
 * comparison, is the same scan in each.
 */
export function declaredColumns<T>(ctx: DeclaredContext, spec: DeclaredSpec<T>): Column<T>[] {
  return [
    {
      id: 'name',
      header: spec.nameHeader,
      accessor: spec.nameOf,
      kind: 'identifier',
      priority: 'essential',
    },
    {
      id: 'declared',
      header: spec.valuesHeader,
      accessor: (item) =>
        spec
          .rowsOf(item)
          .map((r) => `${r.key} ${r.value}`)
          .join(' '),
      cell: (item) => <DeclaredValues rows={spec.rowsOf(item)} empty={spec.noRows} />,
      kind: 'text',
      priority: 'essential',
      // Room for a term and a value in the list, so a long name never squeezes the other into characters.
      min: 32,
      wrap: true,
    },
    {
      id: 'live',
      header: 'On the live nodes',
      accessor: (item) =>
        itemDriftWords(ctx.declaration, spec.section, spec.nameOf(item), spec.queueKeysOf?.(item)).text,
      cell: (item) => <LiveState ctx={ctx} spec={spec} item={item} />,
      kind: 'text',
      priority: 'essential',
      min: 32,
      wrap: true,
    },
    {
      id: 'actions',
      header: 'Actions',
      accessor: () => 'Edit Apply this',
      cell: (item) => <ItemActions ctx={ctx} spec={spec} item={item} />,
      kind: 'status',
      priority: 'essential',
      // Wide enough for both buttons on one line, which the label alone would not reserve.
      min: 20,
      wrap: true,
    },
  ];
}

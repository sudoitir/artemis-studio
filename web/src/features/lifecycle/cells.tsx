import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { TableView } from './api.ts';

/** A table's state in words; only an unhealthy one is marked, so a healthy list stays quiet. */
export function TableState({ table }: Readonly<{ table: TableView }>) {
  return table.problems.length ? (
    <StatusBadge tone="warning">{`Unhealthy: ${table.problems.join('; ')}`}</StatusBadge>
  ) : (
    <StatusBadge>Healthy</StatusBadge>
  );
}

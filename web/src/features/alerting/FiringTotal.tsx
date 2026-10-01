import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { useFiringCounts } from './api.ts';

/** How many alerts are firing across every cluster, in the application header (`shell.header`); nothing while none are. */
export function FiringTotal() {
  const counts = useFiringCounts();
  const total = (counts.data ?? []).reduce((sum, c) => sum + c.firing, 0);
  return total > 0 ? <StatusBadge tone="danger">{`${total} firing`}</StatusBadge> : null;
}

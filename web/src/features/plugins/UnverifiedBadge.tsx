import { StatusBadge } from '../../ui/StatusBadge.tsx';

/** Words carry the meaning; the warning tone is redundant emphasis. */
export function UnverifiedBadge() {
  return <StatusBadge tone="warning">Unverified</StatusBadge>;
}

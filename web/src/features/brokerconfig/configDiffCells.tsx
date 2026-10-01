import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { ConfigEntryView } from './api.ts';

export function ClassificationBadge({ entry }: Readonly<{ entry: ConfigEntryView }>) {
  if (entry.classification === 'EXPECTED') {
    return (
      <span title="Correct by design for two distinct nodes">
        <StatusBadge>expected</StatusBadge>
      </span>
    );
  }
  if (entry.classification === 'UNCLASSIFIED') {
    return (
      <span title="Not known to be configuration — a runtime counter, or an attribute Studio has not classified">
        <StatusBadge>unclassified</StatusBadge>
      </span>
    );
  }
  return entry.drift ? <StatusBadge tone="warning">drift</StatusBadge> : null;
}

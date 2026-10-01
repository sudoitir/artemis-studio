import { LatencyPanel } from './LatencyPanel.tsx';
import { Section } from '../../ui/Section.tsx';

/**
 * Request-reply latency at the foot of a cluster's metrics view (`metrics.panels`). Not a chart
 * panel: latency is a live window rather than persisted history (ADR-0032), so it carries its own
 * coverage disclosure and its own height instead of borrowing the historical panels' fixed box.
 */
export function LatencyCard({ clusterId }: Readonly<{ clusterId: string }>) {
  return (
    <Section variant="card" title="Request-reply latency" description="milliseconds — current live window only">
      <LatencyPanel clusterId={clusterId} />
    </Section>
  );
}

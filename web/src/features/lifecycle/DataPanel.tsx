import { SegmentedControl } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { Page } from '../../ui/Page.tsx';
import { Section } from '../../ui/Section.tsx';
import { HealthTable } from './HealthTable.tsx';
import { RetentionTable } from './RetentionTable.tsx';

/** Administration → Data (ADR-0134): retention and quotas per store, and the tables' storage health. */
export function DataPanel() {
  const search = useSearch({ strict: false }) as { view?: string };
  const navigate = useNavigate();
  const view = search.view === 'health' ? 'health' : 'retention';

  return (
    <Page>
      <SegmentedControl
        w="fit-content"
        aria-label="Data view"
        value={view}
        onChange={(v) =>
          navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, view: v }), replace: true })
        }
        data={[
          { value: 'retention', label: 'Retention' },
          { value: 'health', label: 'Storage health' },
        ]}
      />
      {view === 'retention' ? (
        <Section title="Retention and quotas">
          <RetentionTable />
        </Section>
      ) : (
        <Section title="Storage health">
          <HealthTable />
        </Section>
      )}
    </Page>
  );
}

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { DataPanel } from './DataPanel.tsx';

/** Administration → Data (ADR-0134): one retention home for every store, and storage health. */
export const lifecycleFeature = defineFeature({
  contract: CONTRACT,
  id: 'lifecycle',
  slots: {
    'admin.tabs': [{ id: 'data', order: 45, title: 'Data', Component: DataPanel }],
  },
});

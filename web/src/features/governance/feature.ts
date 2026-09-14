import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { FindingsInbox } from './FindingsInbox.tsx';
import { RulesPanel } from './RulesPanel.tsx';

/** Data governance: the masking rules and classification inbox of the content policy (data-governance spec). */
export const governanceFeature = defineFeature({
  contract: CONTRACT,
  id: 'governance',
  slots: {
    'admin.tabs': [
      { id: 'governance-rules', order: 50, title: 'Masking rules', Component: RulesPanel },
      { id: 'governance-findings', order: 51, title: 'Classification inbox', Component: FindingsInbox },
    ],
  },
});

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { AdminTokensPanel } from './AdminTokensPanel.tsx';
import { ApiKeysSection } from './sections.tsx';

/** API keys: bearer credentials a user issues to scripts and assistants, and the administrators' inventory. */
export const apitokensFeature = defineFeature({
  contract: CONTRACT,
  id: 'apitokens',
  slots: {
    'account.sections': [
      { id: 'apitokens-keys', order: 20, title: 'API keys', group: 'access', Component: ApiKeysSection },
    ],
    'admin.tabs': [{ id: 'api-keys', order: 25, title: 'API keys', group: 'access', Component: AdminTokensPanel }],
  },
});

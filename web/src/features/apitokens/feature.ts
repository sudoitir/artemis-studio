import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { ApiKeysSection } from './sections.tsx';

/** API keys: bearer credentials a user issues to scripts and assistants. */
export const apitokensFeature = defineFeature({
  contract: CONTRACT,
  id: 'apitokens',
  slots: {
    'account.sections': [{ id: 'apitokens-keys', order: 20, title: 'API keys', Component: ApiKeysSection }],
  },
});

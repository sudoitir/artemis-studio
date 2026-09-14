import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { featureView, rootRoute } from '../../kernel/routing/roots.ts';
import { ChangePasswordView } from './ChangePasswordView.tsx';
import { PasswordSection } from './sections.tsx';

const changePasswordRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'change-password',
  component: featureView('identity-local', ChangePasswordView),
});

/** Local username-and-password accounts: changing the password, including the forced change on first sign-in. */
export const identityLocalFeature = defineFeature({
  contract: CONTRACT,
  id: 'identity-local',
  routes: { root: [changePasswordRoute] },
  slots: {
    'account.sections': [{ id: 'identity-local-password', order: 10, title: 'Password', Component: PasswordSection }],
  },
});

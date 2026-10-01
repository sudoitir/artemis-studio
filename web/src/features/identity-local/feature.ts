import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { rootRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { PasswordSection, TwoStepVerificationSection } from './sections.tsx';

const changePasswordRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'change-password',
  component: lazyFeatureView('identity-local', () => import('./ChangePasswordView.tsx'), 'ChangePasswordView'),
});

const enrolSecondFactorRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'enrol-second-factor',
  component: lazyFeatureView('identity-local', () => import('./EnrolSecondFactorView.tsx'), 'EnrolSecondFactorView'),
});

/** Local username-and-password accounts: changing the password, including the forced change on first sign-in, and the two-step verification it can require. */
export const identityLocalFeature = defineFeature({
  contract: CONTRACT,
  id: 'identity-local',
  routes: { root: [changePasswordRoute, enrolSecondFactorRoute] },
  slots: {
    'account.sections': [
      { id: 'identity-local-password', order: 10, title: 'Password', Component: PasswordSection },
      // Right after the password: the two are what signing in asks for.
      {
        id: 'identity-local-two-step',
        order: 12,
        title: 'Two-step verification',
        Component: TwoStepVerificationSection,
      },
    ],
  },
});

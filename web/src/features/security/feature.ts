import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { GroupMappingPanel } from './GroupMappingPanel.tsx';
import { RolesPanel } from './RolesPanel.tsx';
import { SessionsSection } from './sections.tsx';
import { UsersPanel } from './UsersPanel.tsx';

/**
 * Administration of users, roles and grants, and each identity provider's group mappings (authorization spec),
 * and the signed-in user's own sessions on the account page.
 */
export const securityFeature = defineFeature({
  contract: CONTRACT,
  id: 'security',
  slots: {
    // Beside the password: changing it ends the other sessions listed here.
    'account.sections': [{ id: 'security-sessions', order: 15, title: 'Sessions', Component: SessionsSection }],
    'admin.tabs': [
      { id: 'users', order: 10, title: 'Users', Component: UsersPanel },
      { id: 'roles', order: 20, title: 'Roles', Component: RolesPanel },
      { id: 'group-mappings', order: 40, title: 'Group mappings', Component: GroupMappingPanel },
    ],
  },
});

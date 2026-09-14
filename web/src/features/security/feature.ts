import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { GroupMappingPanel } from './GroupMappingPanel.tsx';
import { RolesPanel } from './RolesPanel.tsx';
import { UsersPanel } from './UsersPanel.tsx';

/** Administration of users, roles and grants, and each identity provider's group mappings (authorization spec). */
export const securityFeature = defineFeature({
  contract: CONTRACT,
  id: 'security',
  slots: {
    'admin.tabs': [
      { id: 'users', order: 10, title: 'Users', Component: UsersPanel },
      { id: 'roles', order: 20, title: 'Roles', Component: RolesPanel },
      { id: 'group-mappings', order: 40, title: 'Group mappings', Component: GroupMappingPanel },
    ],
  },
});

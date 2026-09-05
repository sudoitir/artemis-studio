import { Avatar, Menu, Text, UnstyledButton } from '@mantine/core';
import { Link, useNavigate } from '@tanstack/react-router';

import { useLogout, type MeView } from '../api/client.ts';
import { Can } from '../auth/Can.tsx';

export interface UserMenuProps {
  me: MeView | undefined;
}

/** The signed-in user's identity, admin entry point, and logout (identity-and-sessions spec). */
export function UserMenu({ me }: UserMenuProps) {
  const logout = useLogout();
  const navigate = useNavigate();

  if (!me) return null;

  return (
    <Menu position="bottom-end" withArrow>
      <Menu.Target>
        <UnstyledButton aria-label="User menu">
          <Avatar radius="xl" size="sm" color="pine">
            {me.username.slice(0, 2).toUpperCase()}
          </Avatar>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>
          <Text size="sm" fw={600}>
            {me.username}
          </Text>
        </Menu.Label>
        <Can permission="user:admin">
          <Menu.Item component={Link} to="/admin">
            Administration
          </Menu.Item>
        </Can>
        <Menu.Item component={Link} to="/change-password">
          Change password
        </Menu.Item>
        <Menu.Divider />
        <Menu.Item
          color="red"
          onClick={() => logout.mutate(undefined, { onSuccess: () => navigate({ to: '/login' }) })}
        >
          Log out
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

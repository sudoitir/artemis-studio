import { Avatar, Menu, Text, UnstyledButton } from '@mantine/core';
import { useState } from 'react';
import { Link, useNavigate } from '@tanstack/react-router';

import { useLogout, type MeView } from '../auth/api.ts';
import { Can } from '../auth/Can.tsx';
import { useSlot } from '../slots.ts';
import { useDensity, type Density } from '../../ui/table/density.ts';

export interface UserMenuProps {
  me: MeView | undefined;
}

/** The signed-in user's identity, admin entry point, account page, and logout (identity-and-sessions spec). */
export function UserMenu({ me }: Readonly<UserMenuProps>) {
  const logout = useLogout();
  const navigate = useNavigate();
  const dialogs = useSlot('shell.userMenu');
  const [openDialog, setOpenDialog] = useState<string | null>(null);
  const [density, setDensity] = useDensity();

  if (!me) return null;

  return (
    <>
      <Menu position="bottom-end" withArrow>
        <Menu.Target>
          <UnstyledButton aria-label="User menu">
            <Avatar radius="xl" size="sm">
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
          {/* A team admin has no use for the rest of Administration, so their entry opens the Teams tab. */}
          <Can
            permission="user:admin"
            fallback={
              <Can permission="team:admin">
                <Menu.Item component={Link} to="/admin" search={{ tab: 'teams' } as never}>
                  Administration
                </Menu.Item>
              </Can>
            }
          >
            <Menu.Item component={Link} to="/admin">
              Administration
            </Menu.Item>
          </Can>
          {/* Not wrapped in <Can>: every user has an account, and their own keys
            and password live there. */}
          <Menu.Item component={Link} to="/account">
            Account
          </Menu.Item>
          {dialogs.map(({ id, title }) => (
            <Menu.Item key={id} onClick={() => setOpenDialog(id)}>
              {title}
            </Menu.Item>
          ))}
          <Menu.Divider />
          <Menu.Label>Table density</Menu.Label>
          {/* Stays open on a choice: the change is seen in the tables behind the menu, and may be undone. */}
          <Menu.RadioGroup value={density} onChange={(value) => setDensity(value as Density)}>
            <Menu.RadioItem value="compact">Compact</Menu.RadioItem>
            <Menu.RadioItem value="comfortable">Comfortable</Menu.RadioItem>
          </Menu.RadioGroup>
          <Menu.Divider />
          <Menu.Item
            color="signal"
            onClick={() => logout.mutate(undefined, { onSuccess: () => navigate({ to: '/login' }) })}
          >
            Log out
          </Menu.Item>
        </Menu.Dropdown>
      </Menu>
      {dialogs.map(({ id, Component }) => (
        <Component key={id} opened={openDialog === id} onClose={() => setOpenDialog(null)} />
      ))}
    </>
  );
}

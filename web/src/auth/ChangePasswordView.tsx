import { useState } from 'react';
import { Alert, Button, Center, Paper, PasswordInput, Stack, Text, Title } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { useChangePassword, useMe } from '../api/client.ts';

/**
 * Forced password change for the bootstrap admin, or a voluntary change from
 * the user menu (identity-and-sessions spec). Every other request is rejected
 * with `423` until this succeeds when the account is flagged
 * `mustChangePassword` (`MustChangePasswordFilter`).
 */
export function ChangePasswordView() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const changePassword = useChangePassword();
  const me = useMe();
  const navigate = useNavigate();

  const mismatch = confirm.length > 0 && newPassword !== confirm;

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (mismatch || newPassword.length === 0) return;
    changePassword.mutate(
      { currentPassword, newPassword },
      { onSuccess: () => navigate({ to: '/' }) },
    );
  }

  return (
    <Center mih="100vh" bg="var(--as-bg)">
      <Paper w={380} p="xl" radius="md" withBorder>
        <Stack gap="md">
          <Stack gap={2}>
            <Title order={3}>Change your password</Title>
            <Text size="sm" c="dimmed">
              {me.data?.mustChangePassword
                ? 'This account was just created and must set a new password before continuing.'
                : 'Choose a new password for your account.'}
            </Text>
          </Stack>

          <form onSubmit={onSubmit}>
            <Stack gap="sm">
              <PasswordInput
                label="Current password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.currentTarget.value)}
                autoComplete="current-password"
                required
              />
              <PasswordInput
                label="New password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.currentTarget.value)}
                autoComplete="new-password"
                required
              />
              <PasswordInput
                label="Confirm new password"
                value={confirm}
                onChange={(e) => setConfirm(e.currentTarget.value)}
                autoComplete="new-password"
                error={mismatch ? 'Passwords do not match' : undefined}
                required
              />
              {changePassword.isError ? <Alert color="red">Current password is incorrect.</Alert> : null}
              <Button type="submit" loading={changePassword.isPending} fullWidth mt="xs" disabled={mismatch}>
                Change password
              </Button>
            </Stack>
          </form>
        </Stack>
      </Paper>
    </Center>
  );
}

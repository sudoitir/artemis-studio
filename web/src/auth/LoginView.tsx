import { useState } from 'react';
import { Alert, Button, Center, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { branding } from '../branding.ts';
import { ApiError, useLogin } from '../api/client.ts';

/**
 * Local username/password login (identity-and-sessions spec). An OIDC entry
 * point is added here once a provider is configured — see
 * `ArtemisStudioProperties.security` and ADR-0040.
 */
export function LoginView() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const login = useLogin();
  const navigate = useNavigate();

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    login.mutate(
      { username, password },
      {
        onSuccess: (me) => {
          navigate({ to: me.mustChangePassword ? '/change-password' : '/' });
        },
      },
    );
  }

  return (
    <Center mih="100vh" bg="var(--as-bg)">
      <Paper w={360} p="xl" radius="md" withBorder>
        <Stack gap="md">
          <Stack gap={2}>
            <Title order={3}>{branding.productName}</Title>
            <Text size="sm" c="dimmed">
              Sign in to continue
            </Text>
          </Stack>

          <form onSubmit={onSubmit}>
            <Stack gap="sm">
              <TextInput
                label="Username"
                autoFocus
                value={username}
                onChange={(e) => setUsername(e.currentTarget.value)}
                autoComplete="username"
                required
              />
              <PasswordInput
                label="Password"
                value={password}
                onChange={(e) => setPassword(e.currentTarget.value)}
                autoComplete="current-password"
                required
              />
              {login.isError ? <Alert color="red">{loginErrorMessage(login.error)}</Alert> : null}
              <Button type="submit" loading={login.isPending} fullWidth mt="xs">
                Sign in
              </Button>
            </Stack>
          </form>
        </Stack>
      </Paper>
    </Center>
  );
}

function loginErrorMessage(error: ApiError): string {
  if (error.status === 429) return 'Too many attempts. Try again in a moment.';
  return 'Invalid username or password.';
}

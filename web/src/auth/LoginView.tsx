import { useState } from 'react';
import { Alert, Button, Center, Divider, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { branding } from '../branding.ts';
import { ApiError, useAuthProviders, useLogin } from '../api/client.ts';

/**
 * Local username/password login (identity-and-sessions spec), plus an SSO
 * entry point per configured OIDC provider (ADR-0040) — `/auth/providers` is
 * empty when none is configured, so the divider and buttons simply don't render.
 */
export function LoginView() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const login = useLogin();
  const providers = useAuthProviders();
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

          {providers.data && providers.data.length > 0 ? (
            <>
              <Divider label="or" labelPosition="center" />
              <Stack gap="xs">
                {providers.data.map((p) => (
                  <Button key={p.registrationId} component="a" href={p.authorizationUrl} variant="default" fullWidth>
                    Sign in with {p.label}
                  </Button>
                ))}
              </Stack>
            </>
          ) : null}
        </Stack>
      </Paper>
    </Center>
  );
}

function loginErrorMessage(error: ApiError): string {
  if (error.status === 429) return 'Too many attempts. Try again in a moment.';
  return 'Invalid username or password.';
}

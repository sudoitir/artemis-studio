import { useEffect, useRef, useState } from 'react';
import { Button, Divider, Paper, PasswordInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { ApiError, SESSION_ENDED_REASON } from '../api/request.ts';
import {
  useAuthProviders,
  useLogin,
  type AuthResult,
  type IdentityProviderView,
  type MeView,
  type SecondFactorMethod,
} from './api.ts';
import classes from './LoginView.module.css';
import { SecondFactorForm, type Restart } from './SecondFactorForm.tsx';
import { bootState } from '../plugins/boot.ts';

/** The field a refusal is about: Mantine marks it `aria-invalid`, or, for a password field, on its wrapper. */
const INVALID = '[aria-invalid="true"], [data-error] :is(input, textarea)';

/**
 * The login screen, built only from the installation's identity providers
 * (identity-and-sessions spec): a username and password form when a credential
 * provider exists — with a choice when there is more than one — and one sign-in
 * action per redirect provider. While the list loads the form is offered, so a
 * slow request never reads as "sign-in unavailable". An account with a second factor gets a second step
 * once its password is right (ADR-0143); nothing is signed in before it.
 */
export function LoginView() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [provider, setProvider] = useState<string | null>(null);
  const [secondStep, setSecondStep] = useState<{ methods: SecondFactorMethod[]; trustDeviceDays: number } | null>(null);
  const [restart, setRestart] = useState<Restart | null>(null);
  // Back from the second step, the username is right and the password is what needs typing.
  const [returned, setReturned] = useState(false);
  const login = useLogin();
  const providers = useAuthProviders();
  const navigate = useNavigate();

  const chosen = provider ?? providers.data?.find((p) => p.kind === 'CREDENTIAL')?.id ?? null;
  const sessionEnded = new URLSearchParams(globalThis.location.search).get('reason') === SESSION_ENDED_REASON;

  function finish(me: MeView) {
    // Changing the password comes first: nothing else works until it is done.
    const to = landingFor(me);
    // A page that started signed out loaded no plugins (it could not read the manifest), so it
    // starts again, signed in; one that already has them just moves on.
    if (bootState().manifest === undefined) {
      globalThis.location.replace(to);
    } else {
      void navigate({ to });
    }
  }

  function completed(result: AuthResult) {
    if (result.me) finish(result.me);
  }

  function onSubmit(e: React.SubmitEvent) {
    e.preventDefault();
    setRestart(null);
    login.mutate(
      { provider: chosen, username, password },
      {
        onSuccess: (result) => {
          if (result.status === 'SECOND_FACTOR_REQUIRED') {
            setSecondStep({ methods: result.methods ?? [], trustDeviceDays: result.trustDeviceDays });
          } else {
            completed(result);
          }
        },
      },
    );
  }

  function backToPassword(why: Restart | null) {
    setSecondStep(null);
    setReturned(true);
    setPassword('');
    setRestart(why);
    login.reset();
  }

  return (
    <main className={classes.screen}>
      <Paper p="xl" radius="md" withBorder className={classes.card}>
        <Page>
          <PageHeader
            title={secondStep ? 'Two-step verification' : 'Sign in'}
            meta={branding.productName}
            description={secondStep ? 'Confirm it is you with a second step.' : 'Sign in to continue.'}
          />

          {sessionEnded ? (
            <div role="status" className={classes.notice}>
              <Text size="sm" className={classes.title}>
                You were signed out
              </Text>
              <Text size="sm">
                Your session ended after a period of inactivity, reached its maximum length, or was ended from another
                device or by an administrator. Sign in again to continue.
              </Text>
            </div>
          ) : null}

          {restart ? (
            <Text
              size="sm"
              role={restart.failed ? 'alert' : 'status'}
              className={restart.failed ? classes.failure : classes.notice}
            >
              {restart.message}
            </Text>
          ) : null}

          {secondStep ? (
            <SecondFactorForm
              methods={secondStep.methods}
              trustDeviceDays={secondStep.trustDeviceDays}
              onDone={completed}
              onRestart={backToPassword}
              onBack={() => backToPassword(null)}
            />
          ) : (
            <FirstStep
              providers={providers.data}
              loadError={providers.isError ? providers.error : null}
              onRetry={() => void providers.refetch()}
              chosen={chosen}
              onProvider={setProvider}
              username={username}
              onUsername={setUsername}
              password={password}
              onPassword={setPassword}
              returned={returned}
              login={login}
              onSubmit={onSubmit}
            />
          )}
        </Page>
      </Paper>
    </main>
  );
}

/** The password form, when a credential provider exists, and one sign-in action per redirect provider. */
function FirstStep({
  providers,
  loadError,
  onRetry,
  chosen,
  onProvider,
  username,
  onUsername,
  password,
  onPassword,
  returned,
  login,
  onSubmit,
}: Readonly<{
  providers: IdentityProviderView[] | undefined;
  loadError: ApiError | null;
  onRetry: () => void;
  chosen: string | null;
  onProvider: (provider: string | null) => void;
  username: string;
  onUsername: (username: string) => void;
  password: string;
  onPassword: (password: string) => void;
  returned: boolean;
  login: ReturnType<typeof useLogin>;
  onSubmit: (e: React.SubmitEvent) => void;
}>) {
  const credential = (providers ?? []).filter((p) => p.kind === 'CREDENTIAL');
  const redirect = (providers ?? []).filter((p) => p.kind === 'REDIRECT');
  const listed = providers !== undefined;
  const showForm = !listed || credential.length > 0;
  const [left, setLeft] = useState({ username: false, password: false });
  const [rejected, setRejected] = useState(0);
  const form = useRef<HTMLFormElement>(null);

  // What is missing is named beside its field once the field was left or the form was pressed.
  const usernameError = left.username && !username.trim() ? 'Enter your username.' : undefined;
  const passwordError = left.password && !password ? 'Enter your password.' : undefined;

  // A rejected press takes the first invalid field into focus.
  useEffect(() => {
    if (rejected > 0) form.current?.querySelector<HTMLElement>(INVALID)?.focus();
  }, [rejected]);

  function submit(e: React.SubmitEvent) {
    if (!username.trim() || !password) {
      e.preventDefault();
      setLeft({ username: true, password: true });
      setRejected((n) => n + 1);
      return;
    }
    onSubmit(e);
  }

  return (
    <>
      {loadError ? (
        <>
          <ErrorState variant="inline" error={loadError} onRetry={onRetry} />
          <Text size="sm">
            Password sign-in is still offered. Every other way to sign in is missing until this loads.
          </Text>
        </>
      ) : null}
      {showForm ? (
        <form ref={form} noValidate onSubmit={submit}>
          <Stack gap="sm">
            {credential.length > 1 ? (
              <Select
                label="Sign in with"
                data={credential.map((p) => ({ value: p.id, label: p.label }))}
                value={chosen}
                onChange={onProvider}
                allowDeselect={false}
              />
            ) : null}
            <TextInput
              label="Username"
              autoFocus={!returned}
              value={username}
              onChange={(e) => onUsername(e.currentTarget.value)}
              onBlur={() => setLeft((l) => ({ ...l, username: true }))}
              error={usernameError}
              autoComplete="username"
              required
            />
            <PasswordInput
              label="Password"
              value={password}
              onChange={(e) => onPassword(e.currentTarget.value)}
              onBlur={() => setLeft((l) => ({ ...l, password: true }))}
              error={passwordError}
              autoComplete="current-password"
              autoFocus={returned}
              required
            />
            {login.isError ? (
              <Text size="sm" role="alert" className={classes.failure}>
                {loginErrorMessage(login.error)}
              </Text>
            ) : null}
            <Button type="submit" loading={login.isPending} fullWidth>
              Sign in
            </Button>
          </Stack>
        </form>
      ) : null}

      {redirect.length > 0 ? (
        <>
          {showForm ? <Divider label="or" labelPosition="center" /> : null}
          <Stack gap="xs">
            {redirect.map((p) => (
              <Button key={p.id} component="a" href={p.startPath ?? undefined} variant="default" fullWidth>
                Sign in with {p.label}
              </Button>
            ))}
          </Stack>
        </>
      ) : null}

      {listed && !showForm && redirect.length === 0 ? (
        <Text size="sm" role="status" className={classes.notice} data-tone="warning">
          No sign-in method is configured on this installation. An administrator needs to enable local login or
          configure an identity provider.
        </Text>
      ) : null}
    </>
  );
}

/** Where a fresh sign-in goes: what must be done before anything else, then home. */
function landingFor(me: MeView): string {
  if (me.mustChangePassword) return '/change-password';
  if (me.secondFactorEnrolmentRequired) return '/enrol-second-factor';
  return '/';
}

/** A refused attempt says what happened and what to do; it never says which of the two was wrong. */
function loginErrorMessage(error: ApiError): string {
  if (error.status === 429) return 'Too many attempts. Wait a moment, then try again.';
  return 'Invalid username or password. Check both and try again.';
}

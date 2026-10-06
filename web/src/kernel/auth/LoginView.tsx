import { useState } from 'react';
import { Button, Divider, Paper, PasswordInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm, type UseFormReturnType } from '@mantine/form';
import { useNavigate } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { safeHref } from '../../ui/safeHref.ts';
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

type Credentials = { username: string; password: string };

/**
 * The login screen, built only from the installation's identity providers
 * (identity-and-sessions spec): a username and password form when a credential
 * provider exists — with a choice when there is more than one — and one sign-in
 * action per redirect provider. While the list loads the form is offered, so a
 * slow request never reads as "sign-in unavailable". An account with a second factor gets a second step
 * once its password is right (ADR-0143); nothing is signed in before it.
 */
export function LoginView() {
  const form = useForm<Credentials>({
    initialValues: { username: '', password: '' },
    validateInputOnBlur: true,
    validate: {
      username: (v) => (v.trim() ? null : 'Enter your username.'),
      password: (v) => (v ? null : 'Enter your password.'),
    },
  });
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

  const onSubmit = form.onSubmit(({ username, password }) => {
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
  }, focusFirstInvalid(form.getInputNode));

  function backToPassword(why: Restart | null) {
    setSecondStep(null);
    setReturned(true);
    form.setFieldValue('password', '');
    form.clearFieldError('password');
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
            <output className={classes.notice}>
              <Text size="sm" className={classes.title}>
                You were signed out
              </Text>
              <Text size="sm">
                Your session ended after a period of inactivity, reached its maximum length, or was ended from another
                device or by an administrator. Sign in again to continue.
              </Text>
            </output>
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
              form={form}
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
  form,
  returned,
  login,
  onSubmit,
}: Readonly<{
  providers: IdentityProviderView[] | undefined;
  loadError: ApiError | null;
  onRetry: () => void;
  chosen: string | null;
  onProvider: (provider: string | null) => void;
  form: UseFormReturnType<Credentials>;
  returned: boolean;
  login: ReturnType<typeof useLogin>;
  onSubmit: (e?: React.SyntheticEvent<HTMLFormElement>) => void;
}>) {
  const credential = (providers ?? []).filter((p) => p.kind === 'CREDENTIAL');
  const redirect = (providers ?? []).filter((p) => p.kind === 'REDIRECT');
  const listed = providers !== undefined;
  const showForm = !listed || credential.length > 0;
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
        <form noValidate onSubmit={onSubmit}>
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
              {...form.getInputProps('username')}
              autoComplete="username"
              required
            />
            <PasswordInput
              label="Password"
              {...form.getInputProps('password')}
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
              <Button key={p.id} component="a" href={safeHref(p.startPath)} variant="default" fullWidth>
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

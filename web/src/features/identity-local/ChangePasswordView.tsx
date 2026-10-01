import { Button, Paper, PasswordInput, Stack } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useNavigate } from '@tanstack/react-router';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { useChangePassword } from './api.ts';
import classes from './Identity.module.css';
import { useLogout, useMe } from '../../kernel/auth/api.ts';

const CHANGE: ActionVerb = { verb: 'Change', past: 'Changed', progressive: 'Changing' };

/**
 * Forced password change for the bootstrap admin, or a voluntary change from
 * the user menu (identity-and-sessions spec). Every other request is rejected
 * with `423` until this succeeds when the account is flagged
 * `mustChangePassword` (`RestrictedSessionFilter`).
 */
export function ChangePasswordView() {
  const form = useForm({
    initialValues: { currentPassword: '', newPassword: '', confirm: '' },
    validateInputOnBlur: true,
    validate: {
      currentPassword: (v) => (v ? null : 'Enter your current password.'),
      newPassword: (v) => (v ? null : 'Enter a new password.'),
      confirm: (v, values) => {
        if (!v) return 'Repeat the new password.';
        return v === values.newPassword ? null : 'Passwords do not match.';
      },
    },
  });
  const changePassword = useChangePassword();
  const logout = useLogout();
  const me = useMe();
  const navigate = useNavigate();
  const forced = me.data?.mustChangePassword ?? false;

  const error = changePassword.error;
  // The policy's reason and a wrong current password belong beside their fields; anything else is about the attempt.
  const beside = error?.type.endsWith('/password-policy') || error?.status === 401;

  const onSubmit = form.onSubmit(({ currentPassword, newPassword }) => {
    changePassword.mutate(
      { currentPassword, newPassword },
      {
        onSuccess: () => {
          notify.succeeded({ action: CHANGE, subject: 'your password' });
          // First-setup: end the bootstrap session and make the operator sign in
          // with the password they just chose. A voluntary change keeps the
          // session and drops back into the app.
          if (forced) {
            logout.mutate(undefined, { onSettled: () => navigate({ to: '/login' }) });
          } else {
            void navigate({ to: '/' });
          }
        },
        // The server's answer lands on its field, and takes focus there.
        onError: (refused) => {
          if (refused.type.endsWith('/password-policy')) {
            form.setErrors({ newPassword: refused.message });
            form.getInputNode('newPassword')?.focus();
          } else if (refused.status === 401) {
            form.setErrors({ currentPassword: 'Current password is incorrect. Re-enter it and try again.' });
            form.getInputNode('currentPassword')?.focus();
          }
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <main className={classes.screen}>
      <Paper p="xl" radius="md" withBorder className={classes.card}>
        <Page>
          <PageHeader
            title="Change your password"
            description={
              forced
                ? 'This account was just created and must set a new password before continuing.'
                : 'Choose a new password for your account.'
            }
          />

          <form noValidate onSubmit={onSubmit}>
            <Stack gap="sm">
              <PasswordInput
                label="Current password"
                {...form.getInputProps('currentPassword')}
                autoComplete="current-password"
                required
              />
              <PasswordInput
                label="New password"
                {...form.getInputProps('newPassword')}
                autoComplete="new-password"
                required
              />
              <PasswordInput
                label="Confirm new password"
                {...form.getInputProps('confirm')}
                autoComplete="new-password"
                required
              />
              {error && !beside ? <ErrorState variant="inline" error={error} /> : null}
              <Button type="submit" loading={changePassword.isPending || logout.isPending} fullWidth>
                Change password
              </Button>
            </Stack>
          </form>
        </Page>
      </Paper>
    </main>
  );
}

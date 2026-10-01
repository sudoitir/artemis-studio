import { useEffect, useRef, useState } from 'react';
import { Button, Paper, PasswordInput, Stack } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { useChangePassword } from './api.ts';
import classes from './Identity.module.css';
import { useLogout, useMe } from '../../kernel/auth/api.ts';

/** The field a refusal is about: Mantine marks it `aria-invalid`, or, for a password field, on its wrapper. */
const INVALID = '[aria-invalid="true"], [data-error] :is(input, textarea)';

const CHANGE: ActionVerb = { verb: 'Change', past: 'Changed', progressive: 'Changing' };

/**
 * Forced password change for the bootstrap admin, or a voluntary change from
 * the user menu (identity-and-sessions spec). Every other request is rejected
 * with `423` until this succeeds when the account is flagged
 * `mustChangePassword` (`RestrictedSessionFilter`).
 */
export function ChangePasswordView() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [left, setLeft] = useState({ current: false, next: false, confirm: false });
  const [rejected, setRejected] = useState(0);
  const form = useRef<HTMLFormElement>(null);
  const changePassword = useChangePassword();
  const logout = useLogout();
  const me = useMe();
  const navigate = useNavigate();
  const forced = me.data?.mustChangePassword ?? false;

  const error = changePassword.error;
  // The policy's reason belongs beside the field it is about; anything else is about the attempt.
  const policyReason = error?.type.endsWith('/password-policy') ? error.message : undefined;
  const wrongCurrent = error?.status === 401;

  // What is wrong with each field, once it was left or the form was pressed.
  const currentError = wrongCurrent
    ? 'Current password is incorrect. Re-enter it and try again.'
    : left.current && !currentPassword
      ? 'Enter your current password.'
      : undefined;
  const newError = policyReason ?? (left.next && !newPassword ? 'Enter a new password.' : undefined);
  const confirmError =
    left.confirm && confirm.length > 0 && newPassword !== confirm ? 'Passwords do not match.' : undefined;
  const confirmMissing = left.confirm && confirm.length === 0 ? 'Repeat the new password.' : undefined;

  // A rejected press takes the first invalid field into focus.
  useEffect(() => {
    if (rejected > 0) form.current?.querySelector<HTMLElement>(INVALID)?.focus();
  }, [rejected]);

  // The server's answer lands on its field, and takes focus there.
  useEffect(() => {
    if (error) form.current?.querySelector<HTMLElement>(INVALID)?.focus();
  }, [error]);

  function onSubmit(e: React.SubmitEvent) {
    e.preventDefault();
    if (!currentPassword || !newPassword || newPassword !== confirm) {
      setLeft({ current: true, next: true, confirm: true });
      setRejected((n) => n + 1);
      return;
    }
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
      },
    );
  }

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

          <form ref={form} noValidate onSubmit={onSubmit}>
            <Stack gap="sm">
              <PasswordInput
                label="Current password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.currentTarget.value)}
                onBlur={() => setLeft((l) => ({ ...l, current: true }))}
                autoComplete="current-password"
                error={currentError}
                required
              />
              <PasswordInput
                label="New password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.currentTarget.value)}
                onBlur={() => setLeft((l) => ({ ...l, next: true }))}
                autoComplete="new-password"
                error={newError}
                required
              />
              <PasswordInput
                label="Confirm new password"
                value={confirm}
                onChange={(e) => setConfirm(e.currentTarget.value)}
                onBlur={() => setLeft((l) => ({ ...l, confirm: true }))}
                autoComplete="new-password"
                error={confirmError ?? confirmMissing}
                required
              />
              {error && !policyReason && !wrongCurrent ? <ErrorState variant="inline" error={error} /> : null}
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

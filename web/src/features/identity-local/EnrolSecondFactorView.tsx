import { useEffect, useRef, useState } from 'react';
import { Button, Paper } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { useLogout, useMe } from '../../kernel/auth/api.ts';
import { Page } from '../../ui/Page.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import classes from './Identity.module.css';
import { RecoveryCodesDialog } from './RecoveryCodesDialog.tsx';
import { SecondFactorEnrolment } from './SecondFactorEnrolment.tsx';

/**
 * Where a session lands when its role requires a second factor and the account has none (ADR-0143): every
 * other request is refused with `423` until one is enrolled. The recovery codes of the first factor are shown
 * once, and only after the person confirms they saved them does the console open.
 */
export function EnrolSecondFactorView() {
  const me = useMe();
  const logout = useLogout();
  const navigate = useNavigate();
  const [codes, setCodes] = useState<string[] | null>(null);
  // Finishing the enrolment clears the requirement while the codes are still on screen; that is not a reason to leave.
  const wasRequired = useRef(false);
  if (me.data?.secondFactorEnrolmentRequired) wasRequired.current = true;
  // The form starts an enrolment as it opens, which creates a secret on the server; it opens only for an
  // account that must enrol, never for one this page is about to send elsewhere.
  const enrolling = wasRequired.current && !me.data?.mustChangePassword;

  useEffect(() => {
    if (!me.data) return;
    if (me.data.mustChangePassword) void navigate({ to: '/change-password' });
    else if (!me.data.secondFactorEnrolmentRequired && !wasRequired.current) void navigate({ to: '/account' });
  }, [me.data, navigate]);

  return (
    <main className={classes.screen}>
      <Paper p="xl" radius="md" withBorder className={classes.wide}>
        <Page>
          <PageHeader
            title="Set up two-step verification"
            description="Your role requires a second step when you sign in."
          />

          {enrolling ? (
            <SecondFactorEnrolment
              methods={['totp', 'passkey']}
              onEnrolled={(done) => (done.recoveryCodes ? setCodes(done.recoveryCodes) : navigate({ to: '/' }))}
            />
          ) : (
            <LoadingState label="Checking your account" />
          )}

          <Button
            variant="subtle"
            size="compact-sm"
            className={classes.start}
            loading={logout.isPending}
            onClick={() => logout.mutate(undefined, { onSettled: () => navigate({ to: '/login' }) })}
          >
            Sign out
          </Button>
        </Page>
      </Paper>

      <RecoveryCodesDialog
        codes={codes}
        onContinue={() => {
          setCodes(null);
          void navigate({ to: '/' });
        }}
      />
    </main>
  );
}

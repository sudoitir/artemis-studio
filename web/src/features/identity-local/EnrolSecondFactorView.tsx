import { useEffect, useRef, useState } from 'react';
import { Anchor, Center, Paper, Stack, Text, Title } from '@mantine/core';
import { useNavigate } from '@tanstack/react-router';

import { useLogout, useMe } from '../../kernel/auth/api.ts';
import { RecoveryCodesDialog } from './RecoveryCodesDialog.tsx';
import { SecondFactorEnrolment } from './SecondFactorEnrolment.tsx';

/**
 * Where a session lands when its role requires a second factor and the account has none (ADR-0142): every
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

  useEffect(() => {
    if (!me.data) return;
    if (me.data.mustChangePassword) navigate({ to: '/change-password' });
    else if (!me.data.secondFactorEnrolmentRequired && !wasRequired.current) navigate({ to: '/account' });
  }, [me.data, navigate]);

  return (
    <Center mih="100vh" bg="var(--as-bg)">
      <Paper w={560} p="xl" radius="md" withBorder>
        <Stack gap="lg">
          <Stack gap={2}>
            <Title order={3}>Set up two-step verification</Title>
            <Text size="sm" c="dimmed">
              Your role requires a second step when you sign in.
            </Text>
          </Stack>

          <SecondFactorEnrolment
            methods={['totp', 'passkey']}
            onEnrolled={(done) => (done.recoveryCodes ? setCodes(done.recoveryCodes) : navigate({ to: '/' }))}
          />

          <Anchor
            component="button"
            type="button"
            size="sm"
            w="fit-content"
            onClick={() => logout.mutate(undefined, { onSettled: () => navigate({ to: '/login' }) })}
          >
            Sign out
          </Anchor>
        </Stack>
      </Paper>

      <RecoveryCodesDialog
        codes={codes}
        onContinue={() => {
          setCodes(null);
          navigate({ to: '/' });
        }}
      />
    </Center>
  );
}

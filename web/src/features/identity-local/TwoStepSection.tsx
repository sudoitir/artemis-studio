import { useState, type ReactNode } from 'react';
import { Alert, Badge, Button, Group, Loader, Modal, Stack, Text } from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import { needsReauthentication } from '../../kernel/auth/api.ts';
import { describeClient } from '../../kernel/auth/clientLabel.ts';
import { StepUpPrompt } from '../../kernel/auth/StepUp.tsx';
import { passkeysSupported, PASSKEYS_UNSUPPORTED } from '../../kernel/auth/webauthn.ts';
import { Ago } from '../../kernel/time/Ago.tsx';
import { useServerNow } from '../../kernel/time/time.ts';
import { Row, Rows } from '../../ui/ListRows.tsx';
import {
  useMfaStatus,
  useRegenerateRecoveryCodes,
  useRemovePasskey,
  useRemoveTotp,
  useRevokeAllTrustedDevices,
  useRevokeTrustedDevice,
  type MfaStatusView,
  type PasskeyView,
  type TrustedDeviceView,
} from './api.ts';
import { RecoveryCodesDialog } from './RecoveryCodesDialog.tsx';
import { SecondFactorEnrolment, type EnrolMethod } from './SecondFactorEnrolment.tsx';

/** Recovery codes issued at a time (ADR-0142). */
const CODES_ISSUED = 10;
/** At or below this many, running out is close enough to say so. */
const CODES_LOW = 3;

interface Outcome {
  text: string;
  failed: boolean;
}

const returnTo = () => `${window.location.pathname}${window.location.search}`;

/**
 * Account section: the signed-in local user's second factors (ADR-0142) — the authenticator app, passkeys,
 * recovery codes and the browsers they chose to trust — and how to change each. Every change that needs a fresh
 * sign-in asks for it inline; every outcome is announced, and a failure says why and what to do.
 */
export function TwoStepSection() {
  const status = useMfaStatus();

  if (status.isPending) return <Loader size="sm" aria-label="Loading two-step verification" />;
  if (status.isError) {
    return (
      <Alert color="red" variant="light" title="Could not load two-step verification" role="alert">
        <Stack gap="xs" align="flex-start">
          <Text size="sm">{status.error.message} Check your connection and try again.</Text>
          <Button size="xs" variant="light" onClick={() => void status.refetch()}>
            Retry
          </Button>
        </Stack>
      </Alert>
    );
  }
  if (!status.data.local) {
    return (
      <Text size="sm" c="dimmed">
        Your account signs in through your organisation's identity provider, which manages two-step verification. Change
        it there.
      </Text>
    );
  }
  return <TwoStep status={status.data} />;
}

type Pending =
  | { kind: 'enrol'; method: EnrolMethod; replacing: boolean }
  | { kind: 'remove-totp' }
  | { kind: 'remove-passkey'; passkey: PasskeyView }
  | { kind: 'regenerate' };

function TwoStep({ status }: { status: MfaStatusView }) {
  const now = useServerNow(30_000);
  const [pending, setPending] = useState<Pending | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const removeTotp = useRemoveTotp();
  const removePasskey = useRemovePasskey();
  const regenerate = useRegenerateRecoveryCodes();
  const revoke = useRevokeTrustedDevice();
  const revokeAll = useRevokeAllTrustedDevices();

  const passkeyReason = !passkeysSupported()
    ? PASSKEYS_UNSUPPORTED
    : !status.webauthn.available
      ? (status.webauthn.reason ?? 'Passkeys are not available on this installation.')
      : null;
  const factors = status.passkeys.length + (status.totpEnrolled ? 1 : 0);
  const codesLeft = status.recoveryCodesRemaining;
  const busy = revoke.isPending || revokeAll.isPending;

  function close() {
    setPending(null);
    removeTotp.reset();
    removePasskey.reset();
    regenerate.reset();
  }

  function revokeOne(device: TrustedDeviceView) {
    setOutcome(null);
    const label = describeDevice(device);
    revoke.mutate(device.id, {
      onSuccess: () =>
        setOutcome({ text: `Stopped trusting ${label}. It asks for a code at its next sign-in.`, failed: false }),
      onError: (e) =>
        setOutcome(
          e.status === 404
            ? { text: `${label} was no longer trusted.`, failed: false }
            : { text: `Could not revoke ${label}. ${e.message} Try again.`, failed: true },
        ),
    });
  }

  function revokeEvery() {
    setOutcome(null);
    revokeAll.mutate(undefined, {
      onSuccess: () =>
        setOutcome({ text: 'Stopped trusting every device. Each asks for a code at its next sign-in.', failed: false }),
      onError: (e) => setOutcome({ text: `Could not revoke the devices. ${e.message} Try again.`, failed: true }),
    });
  }

  return (
    <Stack gap="lg">
      <Stack gap={4}>
        <Group gap="xs">
          <Text size="sm" fw={600}>
            Two-step verification is {status.enrolled ? 'on' : 'off'}
          </Text>
          {status.required ? (
            <Badge size="sm" variant="default">
              Required by your role
            </Badge>
          ) : null}
        </Group>
        <Text size="sm" c="dimmed">
          {status.enrolled
            ? 'Signing in asks for a code, a passkey or a recovery code as well as your password.'
            : 'Signing in asks for your password only. Add an authenticator app or a passkey to ask for more.'}
        </Text>
      </Stack>

      <Block
        title="Authenticator app"
        detail={
          status.totpEnrolled
            ? 'Set up. Its 6-digit codes complete your sign-in.'
            : 'Not set up. A 6-digit code from an app on your phone.'
        }
        actions={
          status.totpEnrolled ? (
            <>
              <Button
                size="xs"
                variant="default"
                aria-label="Replace authenticator app"
                onClick={() => setPending({ kind: 'enrol', method: 'totp', replacing: true })}
              >
                Replace
              </Button>
              <Button
                size="xs"
                variant="subtle"
                color="red"
                aria-label="Remove authenticator app"
                onClick={() => setPending({ kind: 'remove-totp' })}
              >
                Remove
              </Button>
            </>
          ) : (
            <Button
              size="xs"
              variant="default"
              aria-label="Set up authenticator app"
              onClick={() => setPending({ kind: 'enrol', method: 'totp', replacing: false })}
            >
              Set up
            </Button>
          )
        }
      />

      <Block
        title="Passkeys"
        detailId="passkeys-detail"
        detail={
          passkeyReason
            ? `Not available. ${passkeyReason}`
            : status.passkeys.length === 0
              ? 'None yet. A passkey signs you in with your fingerprint, face or screen lock, or a security key.'
              : undefined
        }
        actions={
          <Button
            size="xs"
            variant="default"
            data-disabled={passkeyReason ? true : undefined}
            aria-disabled={passkeyReason ? true : undefined}
            aria-describedby={passkeyReason ? 'passkeys-detail' : undefined}
            onClick={() => {
              if (!passkeyReason) setPending({ kind: 'enrol', method: 'passkey', replacing: false });
            }}
          >
            Add passkey
          </Button>
        }
      >
        {status.passkeys.length > 0 ? (
          <Rows label="Passkeys">
            {status.passkeys.map((p) => (
              <Row
                key={p.id}
                title={
                  <Text size="sm" fw={500}>
                    {p.label}
                  </Text>
                }
                facts={
                  <>
                    Added <Ago at={p.created} now={now} /> · last used <Ago at={p.lastUsed} now={now} />
                  </>
                }
                action={
                  <Button
                    size="xs"
                    variant="subtle"
                    color="red"
                    aria-label={`Remove passkey ${p.label}`}
                    onClick={() => setPending({ kind: 'remove-passkey', passkey: p })}
                  >
                    Remove
                  </Button>
                }
              />
            ))}
          </Rows>
        ) : null}
      </Block>

      <Block
        title="Recovery codes"
        detailId="recovery-codes-detail"
        detail={
          status.enrolled ? (
            <>
              {codesLeft} of {CODES_ISSUED} left.{' '}
              {codesLeft <= CODES_LOW ? (
                <Text component="span" size="sm" style={{ color: 'var(--as-warning)' }}>
                  {codesLeft === 0 ? 'You have none left.' : 'You are running low.'} Regenerate them before you are
                  locked out.
                </Text>
              ) : (
                'Each signs you in once if you lose your device.'
              )}
            </>
          ) : (
            'Created when you set up your first method.'
          )
        }
        actions={
          <Button
            size="xs"
            variant="default"
            data-disabled={status.enrolled ? undefined : true}
            aria-disabled={status.enrolled ? undefined : true}
            aria-describedby={status.enrolled ? undefined : 'recovery-codes-detail'}
            onClick={() => {
              if (status.enrolled) setPending({ kind: 'regenerate' });
            }}
          >
            Regenerate
          </Button>
        }
      />

      <Block
        title="Trusted devices"
        detail={
          status.trustedDevices.length === 0
            ? 'None. When you sign in, you can trust that browser so it asks for your password only, until the trust expires. Trusted browsers are listed here.'
            : undefined
        }
        actions={
          status.trustedDevices.length > 0 ? (
            <Button size="xs" variant="default" loading={revokeAll.isPending} disabled={busy} onClick={revokeEvery}>
              Revoke all
            </Button>
          ) : null
        }
      >
        {status.trustedDevices.length > 0 ? (
          <Rows label="Trusted devices">
            {status.trustedDevices.map((d) => (
              <Row
                key={d.id}
                title={
                  <Group gap="xs" wrap="wrap">
                    <Text size="sm" fw={500} title={d.client ?? undefined}>
                      {describeClient(d.client)}
                    </Text>
                    {d.current ? (
                      <Badge size="xs" variant="default">
                        This device
                      </Badge>
                    ) : null}
                    <Text size="xs" c="dimmed">
                      {d.address ?? 'Address unknown'}
                    </Text>
                  </Group>
                }
                facts={
                  <>
                    Trusted <Ago at={d.created} now={now} /> · last used <Ago at={d.lastUsed} now={now} /> · expires{' '}
                    <Ago at={d.expires} now={now} future />
                  </>
                }
                action={
                  <Button
                    size="xs"
                    variant="subtle"
                    color="red"
                    aria-label={`Revoke ${describeDevice(d)}`}
                    loading={revoke.isPending && revoke.variables === d.id}
                    disabled={busy}
                    onClick={() => revokeOne(d)}
                  >
                    Revoke
                  </Button>
                }
              />
            ))}
          </Rows>
        ) : null}
      </Block>

      <div aria-live="polite">
        {busy ? (
          <Text size="sm" c="dimmed">
            Revoking…
          </Text>
        ) : outcome ? (
          <Text size="sm" c={outcome.failed ? 'red' : 'dimmed'}>
            {outcome.text}
          </Text>
        ) : null}
      </div>

      <Modal
        opened={pending?.kind === 'enrol'}
        onClose={close}
        size="lg"
        title={
          pending?.kind === 'enrol'
            ? pending.method === 'passkey'
              ? 'Add a passkey'
              : pending.replacing
                ? 'Replace your authenticator app'
                : 'Set up an authenticator app'
            : ''
        }
      >
        {pending?.kind === 'enrol' ? (
          <Stack gap="md">
            {pending.replacing ? (
              <Text size="sm" c="dimmed">
                The app you have now keeps working until you confirm a code from the new one.
              </Text>
            ) : null}
            <SecondFactorEnrolment
              methods={[pending.method]}
              onEnrolled={(done) => {
                const subject =
                  done.method === 'passkey'
                    ? 'Passkey added'
                    : pending.replacing
                      ? 'Authenticator app replaced'
                      : 'Authenticator app set up';
                setOutcome({ text: `${subject}.`, failed: false });
                setPending(null);
                if (done.recoveryCodes) setCodes(done.recoveryCodes);
              }}
            />
          </Stack>
        ) : null}
      </Modal>

      <ConfirmChange
        opened={pending?.kind === 'remove-totp'}
        title="Remove the authenticator app?"
        consequence={removalConsequence('Its codes stop working.', factors, status)}
        blocked={removalBlock(factors, status)}
        confirmLabel="Remove authenticator app"
        pending={removeTotp.isPending}
        error={removeTotp.error}
        onClose={close}
        onConfirm={() =>
          removeTotp.mutate(undefined, {
            onSuccess: () => {
              setOutcome({ text: 'Authenticator app removed.', failed: false });
              close();
            },
          })
        }
      />
      <ConfirmChange
        opened={pending?.kind === 'remove-passkey'}
        title={pending?.kind === 'remove-passkey' ? `Remove passkey "${pending.passkey.label}"?` : ''}
        consequence={removalConsequence('You can no longer sign in with it.', factors, status)}
        blocked={removalBlock(factors, status)}
        confirmLabel="Remove passkey"
        pending={removePasskey.isPending}
        error={removePasskey.error}
        onClose={close}
        onConfirm={() => {
          if (pending?.kind !== 'remove-passkey') return;
          const label = pending.passkey.label;
          removePasskey.mutate(pending.passkey.id, {
            onSuccess: () => {
              setOutcome({ text: `Passkey "${label}" removed.`, failed: false });
              close();
            },
          });
        }}
      />
      <ConfirmChange
        opened={pending?.kind === 'regenerate'}
        title="Regenerate recovery codes?"
        consequence="Your current recovery codes stop working at once. You get 10 new ones, shown once."
        confirmLabel="Regenerate codes"
        pending={regenerate.isPending}
        error={regenerate.error}
        onClose={close}
        onConfirm={() =>
          regenerate.mutate(undefined, {
            onSuccess: (result) => {
              setOutcome({ text: 'Recovery codes regenerated. The old ones no longer work.', failed: false });
              close();
              setCodes(result.codes);
            },
          })
        }
      />

      <RecoveryCodesDialog codes={codes} onContinue={() => setCodes(null)} />
    </Stack>
  );
}

const describeDevice = (d: TrustedDeviceView) => `${describeClient(d.client)} at ${d.address ?? 'an unknown address'}`;

/** What removing a factor costs: the factor itself and, when it is the last, everything that hangs on having one. */
function removalConsequence(lost: string, factors: number, status: MfaStatusView): string {
  if (factors > 1) return `${lost} Your other methods and your recovery codes keep working.`;
  return status.required
    ? `${lost} It is your only method.`
    : `${lost} It is your only method, so two-step verification turns off: your recovery codes are deleted and every trusted device is revoked.`;
}

/** The server refuses to remove the last factor of a user whose role requires one; say so before the click. */
function removalBlock(factors: number, status: MfaStatusView): string | null {
  return status.required && factors <= 1 ? 'Your role requires two-step verification. Add another method first.' : null;
}

function Block({
  title,
  detail,
  detailId,
  actions,
  children,
}: {
  title: string;
  detail?: ReactNode;
  detailId?: string;
  actions?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <Stack gap="xs">
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <Stack gap={2}>
          <Text size="sm" fw={600}>
            {title}
          </Text>
          {detail ? (
            <Text id={detailId} size="sm" c="dimmed">
              {detail}
            </Text>
          ) : null}
        </Stack>
        <Group gap="xs" wrap="nowrap">
          {actions}
        </Group>
      </Group>
      {children}
    </Stack>
  );
}

/**
 * A confirmation for a change to the account's factors: what it costs first, then a fresh sign-in when the server
 * asks for one, then the button that names the action. A refusal says why, beside it.
 */
function ConfirmChange({
  opened,
  title,
  consequence,
  blocked,
  confirmLabel,
  pending,
  error,
  onClose,
  onConfirm,
}: {
  opened: boolean;
  title: string;
  consequence: string;
  blocked?: string | null;
  confirmLabel: string;
  pending: boolean;
  error: ApiError | null;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const refusal = error && !needsReauthentication(error) ? error : null;
  return (
    <Modal opened={opened} onClose={onClose} title={title}>
      <Stack gap="md">
        <Text size="sm">{consequence}</Text>
        {blocked ? (
          <Text size="sm" fw={500}>
            {blocked}
          </Text>
        ) : null}
        <StepUpPrompt error={error} returnTo={returnTo()} />
        {refusal ? (
          <Alert color="red" variant="light" role="alert" title="Not done">
            {refusal.message} {refusal.status === 409 ? '' : 'Try again.'}
          </Alert>
        ) : null}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button color="red" loading={pending} disabled={!!blocked} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

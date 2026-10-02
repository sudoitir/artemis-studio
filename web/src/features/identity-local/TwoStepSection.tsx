import { useState, type ReactNode } from 'react';
import { Button, Modal, Stack, Text } from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import { needsReauthentication } from '../../kernel/auth/api.ts';
import { describeClient } from '../../kernel/auth/clientLabel.ts';
import { StepUpPrompt } from '../../kernel/auth/StepUp.tsx';
import { passkeyUnavailableReason } from '../../kernel/auth/webauthn.ts';
import { Ago } from '../../kernel/time/Ago.tsx';
import { useServerNow } from '../../kernel/time/time.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Row, Rows } from '../../ui/ListRows.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
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
import { SecondFactorEnrolment, type EnrolMethod, type Enrolled } from './SecondFactorEnrolment.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import classes from './Identity.module.css';

/** Recovery codes issued at a time (ADR-0143). */
const CODES_ISSUED = 10;
/** At or below this many, running out is close enough to say so. */
const CODES_LOW = 3;

interface Outcome {
  text: string;
  failed: boolean;
}

const returnTo = () => `${globalThis.location.pathname}${globalThis.location.search}`;

/** The height of the section for an account with an authenticator app, recovery codes and no passkeys or trusted devices. */
const SECTION_BLOCK = '20.25rem';

/**
 * Account section: the signed-in password user's second factors, local or a plugin's sign-in (ADR-0143, ADR-0156) — the authenticator app, passkeys,
 * recovery codes and the browsers they chose to trust — and how to change each. Every change that needs a fresh
 * sign-in asks for it inline; every outcome is announced, and a failure says why and what to do.
 */
export function TwoStepSection() {
  const status = useMfaStatus();

  // The frame holds the height of the sections that replace it, and the failure the same, so nothing below
  // moves when either arrives.
  if (status.isPending) return <LoadingState label="Loading two-step verification" blockSize={SECTION_BLOCK} />;
  if (status.isError) {
    return <ErrorState error={status.error} onRetry={() => void status.refetch()} blockSize={SECTION_BLOCK} />;
  }
  if (!status.data.passwordAccount) {
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

function TwoStep({ status }: Readonly<{ status: MfaStatusView }>) {
  const now = useServerNow(30_000);
  const [pending, setPending] = useState<Pending | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const factors = status.passkeys.length + (status.totpEnrolled ? 1 : 0);
  const devices = useDeviceRevocation(setOutcome);

  const close = () => setPending(null);
  const done = (text: string) => setOutcome({ text, failed: false });

  return (
    <Stack gap="lg">
      <Summary status={status} />
      <TotpBlock status={status} factors={factors} onChange={setPending} />
      <PasskeysBlock status={status} factors={factors} now={now} onChange={setPending} />
      <RecoveryCodesBlock status={status} onRegenerate={() => setPending({ kind: 'regenerate' })} />
      <TrustedDevicesBlock devices={devices} trusted={status.trustedDevices} now={now} />

      <div aria-live="polite">
        {devices.busy ? (
          <Text size="sm" c="dimmed">
            Revoking…
          </Text>
        ) : null}
        {!devices.busy && outcome ? (
          <Text
            size="sm"
            c={outcome.failed ? undefined : 'dimmed'}
            className={outcome.failed ? classes.failure : undefined}
          >
            {outcome.text}
          </Text>
        ) : null}
      </div>

      <EnrolModal
        pending={pending}
        onClose={close}
        onEnrolled={(result, replacing) => {
          done(`${enrolledSubject(result.method, replacing)}.`);
          close();
          if (result.recoveryCodes) setCodes(result.recoveryCodes);
        }}
      />
      <RemoveTotpConfirm
        opened={pending?.kind === 'remove-totp'}
        factors={factors}
        status={status}
        onClose={close}
        onDone={done}
      />
      <RemovePasskeyConfirm pending={pending} factors={factors} status={status} onClose={close} onDone={done} />
      <RegenerateConfirm
        opened={pending?.kind === 'regenerate'}
        onClose={close}
        onDone={(text, fresh) => {
          done(text);
          setCodes(fresh);
        }}
      />

      <RecoveryCodesDialog codes={codes} onContinue={() => setCodes(null)} />
    </Stack>
  );
}

function Summary({ status }: Readonly<{ status: MfaStatusView }>) {
  return (
    <Stack gap="xs">
      <div className={classes.controls}>
        <Text size="sm" fw={600}>
          Two-step verification is {status.enrolled ? 'on' : 'off'}
        </Text>
        {status.required ? <StatusBadge>Required by your role</StatusBadge> : null}
      </div>
      <Text size="sm" c="dimmed">
        {status.enrolled
          ? 'Signing in asks for a code, a passkey or a recovery code as well as your password.'
          : 'Signing in asks for your password only. Add an authenticator app or a passkey to ask for more.'}
      </Text>
    </Stack>
  );
}

/** A control that is not available now: it stays, says why through the text it is described by, and does nothing. */
function unavailable(reason: string | null, describedBy: string) {
  return reason
    ? ({ 'data-disabled': true, 'aria-disabled': true, 'aria-describedby': describedBy } as const)
    : ({} as const);
}

function TotpBlock({
  status,
  factors,
  onChange,
}: Readonly<{ status: MfaStatusView; factors: number; onChange: (next: Pending) => void }>) {
  const blocked = status.totpEnrolled ? removalBlock(factors, status) : null;
  return (
    <Block
      title="Authenticator app"
      detailId="totp-detail"
      detail={
        status.totpEnrolled ? (
          <>
            Set up. Its 6-digit codes complete your sign-in.
            {blocked ? ` ${blocked}` : ''}
          </>
        ) : (
          'Not set up. A 6-digit code from an app on your phone.'
        )
      }
      actions={
        status.totpEnrolled ? (
          <>
            <Button
              size="xs"
              variant="default"
              aria-label="Replace authenticator app"
              onClick={() => onChange({ kind: 'enrol', method: 'totp', replacing: true })}
            >
              Replace
            </Button>
            <Button
              size="xs"
              variant="subtle"
              aria-label="Remove authenticator app"
              {...unavailable(blocked, 'totp-detail')}
              onClick={() => {
                if (!blocked) onChange({ kind: 'remove-totp' });
              }}
            >
              Remove
            </Button>
          </>
        ) : (
          <Button
            size="xs"
            variant="default"
            aria-label="Set up authenticator app"
            onClick={() => onChange({ kind: 'enrol', method: 'totp', replacing: false })}
          >
            Set up
          </Button>
        )
      }
    />
  );
}

function passkeysDetailOf(status: MfaStatusView, reason: string | null, blocked: string | null): string | undefined {
  if (reason) return `Not available. ${reason}`;
  if (status.passkeys.length === 0) {
    return 'None yet. A passkey signs you in with your fingerprint, face or screen lock, or a security key.';
  }
  return blocked ?? undefined;
}

function PasskeysBlock({
  status,
  factors,
  now,
  onChange,
}: Readonly<{ status: MfaStatusView; factors: number; now: number; onChange: (next: Pending) => void }>) {
  const reason = passkeyUnavailableReason(status.webauthn);
  const blocked = removalBlock(factors, status);
  return (
    <Block
      title="Passkeys"
      detailId="passkeys-detail"
      detail={passkeysDetailOf(status, reason, blocked)}
      actions={
        <Button
          size="xs"
          variant="default"
          data-disabled={reason ? true : undefined}
          aria-disabled={reason ? true : undefined}
          aria-describedby={reason ? 'passkeys-detail' : undefined}
          onClick={() => {
            if (!reason) onChange({ kind: 'enrol', method: 'passkey', replacing: false });
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
                  aria-label={`Remove passkey ${p.label}`}
                  {...unavailable(blocked, 'passkeys-detail')}
                  onClick={() => {
                    if (!blocked) onChange({ kind: 'remove-passkey', passkey: p });
                  }}
                >
                  Remove
                </Button>
              }
            />
          ))}
        </Rows>
      ) : null}
    </Block>
  );
}

function recoveryCodesDetailOf(status: MfaStatusView): ReactNode {
  if (!status.enrolled) return 'Created when you set up your first method.';
  const left = status.recoveryCodesRemaining;
  return (
    <>
      {left} of {CODES_ISSUED} left.{' '}
      {left <= CODES_LOW ? (
        <Text component="span" size="sm" className={classes.warning}>
          {left === 0 ? 'You have none left.' : 'You are running low.'} Regenerate them before you are locked out.
        </Text>
      ) : (
        'Each signs you in once if you lose your device.'
      )}
    </>
  );
}

function RecoveryCodesBlock({ status, onRegenerate }: Readonly<{ status: MfaStatusView; onRegenerate: () => void }>) {
  return (
    <Block
      title="Recovery codes"
      detailId="recovery-codes-detail"
      detail={recoveryCodesDetailOf(status)}
      actions={
        <Button
          size="xs"
          variant="default"
          data-disabled={status.enrolled ? undefined : true}
          aria-disabled={status.enrolled ? undefined : true}
          aria-describedby={status.enrolled ? undefined : 'recovery-codes-detail'}
          onClick={() => {
            if (status.enrolled) onRegenerate();
          }}
        >
          Regenerate
        </Button>
      }
    />
  );
}

/** Revoking trusted devices, one or all, and saying how it went. */
function useDeviceRevocation(setOutcome: (outcome: Outcome | null) => void) {
  const revoke = useRevokeTrustedDevice();
  const revokeAll = useRevokeAllTrustedDevices();

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

  return {
    busy: revoke.isPending || revokeAll.isPending,
    revokingAll: revokeAll.isPending,
    revoking: revoke.isPending ? revoke.variables : undefined,
    revokeOne,
    revokeEvery,
  };
}

function TrustedDevicesBlock({
  devices,
  trusted,
  now,
}: Readonly<{ devices: ReturnType<typeof useDeviceRevocation>; trusted: TrustedDeviceView[]; now: number }>) {
  const any = trusted.length > 0;
  return (
    <Block
      title="Trusted devices"
      detail={
        any
          ? undefined
          : 'None. When you sign in, you can trust that browser so it asks for your password only, until the trust expires. Trusted browsers are listed here.'
      }
      actions={
        any ? (
          <Button
            size="xs"
            variant="default"
            loading={devices.revokingAll}
            disabled={devices.busy}
            onClick={devices.revokeEvery}
          >
            Revoke all
          </Button>
        ) : null
      }
    >
      {any ? (
        <Rows label="Trusted devices">
          {trusted.map((d) => (
            <Row
              key={d.id}
              title={
                <div className={classes.controls}>
                  <Text size="sm" fw={500} title={d.client ?? undefined}>
                    {describeClient(d.client)}
                  </Text>
                  {d.current ? <StatusBadge>This device</StatusBadge> : null}
                </div>
              }
              facts={
                <>
                  {d.address ?? 'Address unknown'} · trusted <Ago at={d.created} now={now} /> · last used{' '}
                  <Ago at={d.lastUsed} now={now} /> · expires <Ago at={d.expires} now={now} future />
                </>
              }
              action={
                <Button
                  size="xs"
                  variant="subtle"
                  aria-label={`Revoke ${describeDevice(d)}`}
                  loading={devices.revoking === d.id}
                  disabled={devices.busy}
                  onClick={() => devices.revokeOne(d)}
                >
                  Revoke
                </Button>
              }
            />
          ))}
        </Rows>
      ) : null}
    </Block>
  );
}

function EnrolModal({
  pending,
  onClose,
  onEnrolled,
}: Readonly<{
  pending: Pending | null;
  onClose: () => void;
  onEnrolled: (result: Enrolled, replacing: boolean) => void;
}>) {
  const enrol = pending?.kind === 'enrol' ? pending : null;
  return (
    <Modal opened={enrol !== null} onClose={onClose} size="lg" title={enrol ? enrolTitle(enrol) : ''}>
      {enrol ? (
        <Stack gap="md">
          {enrol.replacing ? (
            <Text size="sm" c="dimmed">
              The app you have now keeps working until you confirm a code from the new one.
            </Text>
          ) : null}
          <SecondFactorEnrolment
            methods={[enrol.method]}
            onEnrolled={(result) => onEnrolled(result, enrol.replacing)}
          />
        </Stack>
      ) : null}
    </Modal>
  );
}

type ConfirmProps = Readonly<{
  factors: number;
  status: MfaStatusView;
  onClose: () => void;
  onDone: (text: string) => void;
}>;

function RemoveTotpConfirm({ opened, factors, status, onClose, onDone }: ConfirmProps & Readonly<{ opened: boolean }>) {
  const removeTotp = useRemoveTotp();
  const close = () => {
    removeTotp.reset();
    onClose();
  };
  return (
    <ConfirmChange
      opened={opened}
      title="Remove the authenticator app?"
      consequence={removalConsequence('Its codes stop working.', factors, status)}
      confirmLabel="Remove authenticator app"
      pending={removeTotp.isPending}
      error={removeTotp.error}
      onClose={close}
      onConfirm={() =>
        removeTotp.mutate(undefined, {
          onSuccess: () => {
            onDone('Authenticator app removed.');
            close();
          },
        })
      }
    />
  );
}

function RemovePasskeyConfirm({
  pending,
  factors,
  status,
  onClose,
  onDone,
}: ConfirmProps & Readonly<{ pending: Pending | null }>) {
  const removePasskey = useRemovePasskey();
  const passkey = pending?.kind === 'remove-passkey' ? pending.passkey : null;
  const close = () => {
    removePasskey.reset();
    onClose();
  };
  return (
    <ConfirmChange
      opened={passkey !== null}
      title={passkey ? `Remove passkey "${passkey.label}"?` : ''}
      consequence={removalConsequence('You can no longer sign in with it.', factors, status)}
      confirmLabel="Remove passkey"
      typedName={passkey?.label}
      pending={removePasskey.isPending}
      error={removePasskey.error}
      onClose={close}
      onConfirm={() => {
        if (!passkey) return;
        removePasskey.mutate(passkey.id, {
          onSuccess: () => {
            onDone(`Passkey "${passkey.label}" removed.`);
            close();
          },
        });
      }}
    />
  );
}

function RegenerateConfirm({
  opened,
  onClose,
  onDone,
}: Readonly<{ opened: boolean; onClose: () => void; onDone: (text: string, codes: string[]) => void }>) {
  const regenerate = useRegenerateRecoveryCodes();
  const close = () => {
    regenerate.reset();
    onClose();
  };
  return (
    <ConfirmChange
      opened={opened}
      title="Regenerate recovery codes?"
      consequence="Your current recovery codes stop working at once. You get 10 new ones, shown once."
      confirmLabel="Regenerate codes"
      tone="default"
      pending={regenerate.isPending}
      error={regenerate.error}
      onClose={close}
      onConfirm={() =>
        regenerate.mutate(undefined, {
          onSuccess: (result) => {
            onDone('Recovery codes regenerated. The old ones no longer work.', result.codes);
            close();
          },
        })
      }
    />
  );
}

const enrolTitle = ({ method, replacing }: { method: EnrolMethod; replacing: boolean }) => {
  if (method === 'passkey') return 'Add a passkey';
  return replacing ? 'Replace your authenticator app' : 'Set up an authenticator app';
};

const enrolledSubject = (method: EnrolMethod, replacing: boolean) => {
  if (method === 'passkey') return 'Passkey added';
  return replacing ? 'Authenticator app replaced' : 'Authenticator app set up';
};

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
}: Readonly<{
  title: string;
  detail?: ReactNode;
  detailId?: string;
  actions?: ReactNode;
  children?: ReactNode;
}>) {
  return (
    <Stack gap="xs">
      <div className={classes.block}>
        <Stack gap={0} className={classes.blockText}>
          <Text size="sm" fw={600}>
            {title}
          </Text>
          {detail ? (
            <Text id={detailId} size="sm" c="dimmed">
              {detail}
            </Text>
          ) : null}
        </Stack>
        <div className={classes.controls}>{actions}</div>
      </div>
      {children}
    </Stack>
  );
}

/**
 * A confirmation for a change to the account's factors: what it costs first, then a fresh sign-in when the server
 * asks for one, then the button that names the action. A refusal says why, beside it. A removal that is not
 * allowed (a role that requires a factor) is refused at its own button, before this opens.
 */
function ConfirmChange({
  opened,
  title,
  consequence,
  confirmLabel,
  typedName,
  tone = 'danger',
  pending,
  error,
  onClose,
  onConfirm,
}: Readonly<{
  opened: boolean;
  title: string;
  consequence: string;
  confirmLabel: string;
  /** The name of what is removed, typed to arm the button. */
  typedName?: string;
  tone?: 'default' | 'danger';
  pending: boolean;
  error: ApiError | null;
  onClose: () => void;
  onConfirm: () => void;
}>) {
  const refusal = error && !needsReauthentication(error) ? error : null;
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={title}
      consequence={
        <Stack gap="md">
          <span>{consequence}</span>
          <StepUpPrompt error={error} returnTo={returnTo()} />
          {refusal ? <ErrorState variant="inline" error={refusal} /> : null}
        </Stack>
      }
      confirmLabel={confirmLabel}
      tone={tone}
      typedName={typedName}
      pending={pending}
      onConfirm={onConfirm}
    />
  );
}

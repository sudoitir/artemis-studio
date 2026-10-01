import { useEffect, useRef, useState } from 'react';
import { Button, Code, CopyButton, Radio, Stack, Text, TextInput, VisuallyHidden } from '@mantine/core';
import { IconCheck, IconCopy, IconKey } from '@tabler/icons-react';

import { ApiError } from '../../kernel/api/request.ts';
import { needsReauthentication } from '../../kernel/auth/api.ts';
import { describeBrowser } from '../../kernel/auth/clientLabel.ts';
import { useFreshSignIn } from '../../kernel/auth/freshSignIn.ts';
import { StepUpPrompt } from '../../kernel/auth/StepUp.tsx';
import { createPasskey, passkeyFailure, passkeyUnavailableReason } from '../../kernel/auth/webauthn.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { fetchPasskeyCreationOptions, useConfirmTotp, useMfaStatus, useRegisterPasskey, useStartTotp } from './api.ts';
import classes from './Identity.module.css';
import { QrCode } from './QrCode.tsx';

export type EnrolMethod = 'totp' | 'passkey';

/** What a finished enrolment hands back: recovery codes, when this was the account's first factor (shown once). */
export interface Enrolled {
  method: EnrolMethod;
  recoveryCodes: string[] | null;
}

const CODE = /^\d{6}$/;

const returnTo = () => `${globalThis.location.pathname}${globalThis.location.search}`;

/** The key as it is typed into an app: groups of four. */
const groupedKey = (secret: string) => secret.replace(/(.{4})(?=.)/g, '$1 ');

/**
 * Sets up a second factor: an authenticator app (a QR code, its key as text, and a code to prove it works) or a
 * passkey. With more than one method the person chooses, the authenticator app first; an unavailable passkey stays
 * in the list, says why in words, and can be focused so the reason is read to a keyboard user too. Whichever
 * method finishes reports through `onEnrolled`; the recovery codes it may carry are the caller's to show.
 */
export function SecondFactorEnrolment({
  methods,
  onEnrolled,
}: Readonly<{
  methods: EnrolMethod[];
  onEnrolled: (result: Enrolled) => void;
}>) {
  const status = useMfaStatus();
  const [choice, setChoice] = useState<EnrolMethod>(methods[0]);

  // Unknown is not unavailable: until the server has said, passkeys stay selectable.
  const passkeyReason = passkeyUnavailableReason(status.data?.webauthn);

  return (
    <Stack gap="lg">
      {methods.length > 1 ? (
        <Radio.Group
          label="Choose how you will verify"
          value={choice}
          onChange={(value) => {
            if (value === 'passkey' && passkeyReason) return;
            setChoice(value as EnrolMethod);
          }}
        >
          <Stack gap="sm" mt="xs">
            <Radio
              value="totp"
              label="Authenticator app"
              description="A 6-digit code from an app on your phone, such as Google Authenticator, 1Password or Aegis."
            />
            <Radio
              value="passkey"
              label="Passkey"
              description={
                passkeyReason
                  ? `Not available. ${passkeyReason}`
                  : 'Your fingerprint, face or screen lock, or a security key.'
              }
              aria-disabled={passkeyReason ? true : undefined}
              styles={passkeyReason ? { radio: { cursor: 'not-allowed' } } : undefined}
            />
          </Stack>
        </Radio.Group>
      ) : null}

      {choice === 'totp' ? <AuthenticatorSetup onEnrolled={onEnrolled} /> : <PasskeySetup onEnrolled={onEnrolled} />}
    </Stack>
  );
}

function AuthenticatorSetup({ onEnrolled }: Readonly<{ onEnrolled: (result: Enrolled) => void }>) {
  const start = useStartTotp();
  const confirm = useConfirmTotp();
  const fresh = useFreshSignIn();
  const [code, setCode] = useState('');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const field = useRef<HTMLInputElement>(null);
  const started = useRef(false);
  const wasFresh = useRef(fresh);

  // Once, when this appears: the QR code is the point of the screen, so it needs no button first. (A ref, not a
  // dependency, so a development double-mount does not begin twice and show a key the server has replaced.)
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    start.mutate();
  }, [start]);

  // Replacing an authenticator needs a fresh sign-in; once it is confirmed, begin again without a button.
  useEffect(() => {
    if (fresh && !wasFresh.current && needsReauthentication(start.error)) start.mutate();
    wasFresh.current = fresh;
  }, [fresh, start]);

  function submit(e: React.SubmitEvent) {
    e.preventDefault();
    const digits = code.replace(/\s/g, '');
    if (!CODE.test(digits)) {
      setFieldError('Enter the 6 digits your app shows.');
      field.current?.focus();
      return;
    }
    confirm.mutate(digits, {
      onSuccess: (result) => {
        onEnrolled({ method: 'totp', recoveryCodes: result.recoveryCodes ?? null });
        // The key and the codes are the caller's now (or nobody's): the cache keeps neither.
        confirm.reset();
        start.reset();
      },
      onError: (error) => {
        if (error.status === 400) {
          setFieldError(
            'That code is not right. Enter the code your app shows now; if it keeps failing, check the clock on your phone.',
          );
          field.current?.focus();
        }
      },
    });
  }

  const setup = start.data;
  const confirmFailure =
    confirm.error && confirm.error.status !== 400 && !needsReauthentication(confirm.error) ? confirm.error : null;
  const startFailure = start.error && !needsReauthentication(start.error) ? start.error : null;

  return (
    <Stack gap="md">
      <StepUpPrompt error={start.error} returnTo={returnTo()} />
      {startFailure ? <ErrorState variant="inline" error={startFailure} onRetry={() => start.mutate()} /> : null}

      {setup ? (
        <>
          <div className={classes.setup}>
            <Stack gap="xs">
              <Text size="sm" fw={600}>
                Scan with your authenticator app
              </Text>
              <QrCode value={setup.otpauthUri} label="QR code for your authenticator app" />
            </Stack>
            <Stack gap="xs" className={classes.secret}>
              <Text size="sm" fw={600}>
                Can't scan? Enter this key
              </Text>
              <Code block className={classes.secretCode}>
                {groupedKey(setup.secret)}
              </Code>
              <CopyButton value={setup.secret}>
                {({ copied, copy }) => (
                  <>
                    <Button
                      variant="default"
                      size="xs"
                      className={classes.start}
                      leftSection={
                        copied ? <IconCheck size="0.875rem" aria-hidden /> : <IconCopy size="0.875rem" aria-hidden />
                      }
                      onClick={copy}
                    >
                      {copied ? 'Copied' : 'Copy key'}
                    </Button>
                    <VisuallyHidden aria-live="polite">{copied ? 'Key copied to the clipboard.' : ''}</VisuallyHidden>
                  </>
                )}
              </CopyButton>
              <Text size="xs" c="dimmed">
                Choose "enter a setup key" in your app and pick the time-based option.
              </Text>
            </Stack>
          </div>

          <form onSubmit={submit} noValidate>
            <Stack gap="sm" align="flex-start">
              <TextInput
                ref={field}
                label="Enter the 6-digit code"
                description="Shown by your app once the key is added."
                value={code}
                onChange={(e) => {
                  setCode(e.currentTarget.value);
                  setFieldError(null);
                }}
                onBlur={() =>
                  code && !CODE.test(code.replace(/\s/g, '')) && setFieldError('Enter the 6 digits your app shows.')
                }
                error={fieldError}
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={7}
                className={classes.field}
              />
              <Button type="submit" loading={confirm.isPending}>
                Confirm
              </Button>
            </Stack>
          </form>
          <div aria-live="polite">
            {confirm.isPending ? (
              <Text size="sm" c="dimmed">
                Checking the code…
              </Text>
            ) : null}
          </div>
          {confirmFailure ? (
            <ErrorState variant="inline" error={confirmFailure} onRetry={() => confirm.reset()} />
          ) : null}
        </>
      ) : null}
      {!setup && start.isPending ? <LoadingState label="Preparing your setup key" blockSize="24rem" /> : null}
    </Stack>
  );
}

function PasskeySetup({ onEnrolled }: Readonly<{ onEnrolled: (result: Enrolled) => void }>) {
  const register = useRegisterPasskey();
  const [label, setLabel] = useState(() => describeBrowser(navigator.userAgent) ?? 'This device');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  const field = useRef<HTMLInputElement>(null);

  async function create(e: React.SubmitEvent) {
    e.preventDefault();
    if (!label.trim()) {
      setFieldError('Name this passkey so you can tell it apart later.');
      field.current?.focus();
      return;
    }
    setFailure(null);
    setNotice(null);
    setAsking(true);
    try {
      const credential = await createPasskey(await fetchPasskeyCreationOptions());
      if (credential === null) {
        setNotice('Passkey prompt was dismissed. Try again when you are ready.');
        return;
      }
      const created = await register.mutateAsync({ label: label.trim(), credential });
      onEnrolled({ method: 'passkey', recoveryCodes: created.recoveryCodes ?? null });
      register.reset();
    } catch (error) {
      setFailure(error);
    } finally {
      setAsking(false);
    }
  }

  const problem = failure instanceof ApiError ? failure : null;
  const refused = failure !== null && !needsReauthentication(failure);

  return (
    <Stack gap="md">
      <Text size="sm">
        Your browser asks you to confirm with your fingerprint, face, screen lock or security key, then keeps a passkey
        for this account.
      </Text>
      <StepUpPrompt error={failure} returnTo={returnTo()} />
      <form onSubmit={(e) => void create(e)} noValidate>
        <Stack gap="sm" align="flex-start">
          <TextInput
            ref={field}
            label="Passkey name"
            description="So you can tell your passkeys apart, for example the device it lives on."
            value={label}
            onChange={(e) => {
              setLabel(e.currentTarget.value);
              setFieldError(null);
            }}
            error={fieldError}
            maxLength={100}
            className={classes.nameField}
          />
          <Button type="submit" leftSection={<IconKey size="1rem" aria-hidden />} loading={asking}>
            Create passkey
          </Button>
        </Stack>
      </form>
      <div aria-live="polite">
        {asking ? (
          <Text size="sm" c="dimmed">
            Waiting for your passkey…
          </Text>
        ) : null}
        {!asking && notice ? <Text size="sm">{notice}</Text> : null}
      </div>
      {refused && problem ? <ErrorState variant="inline" error={problem} onRetry={() => setFailure(null)} /> : null}
      {refused && !problem ? (
        <Text size="sm" role="alert" className={classes.failure}>
          Passkey not created. {passkeyFailure(failure)}
        </Text>
      ) : null}
    </Stack>
  );
}

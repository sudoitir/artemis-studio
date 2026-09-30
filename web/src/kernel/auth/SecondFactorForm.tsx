import { useRef, useState } from 'react';
import { Alert, Anchor, Button, Checkbox, Divider, Stack, Text, TextInput } from '@mantine/core';
import { IconArrowLeft, IconKey } from '@tabler/icons-react';

import { ApiError } from '../api/request.ts';
import {
  fetchPasskeyRequestOptions,
  useSecondFactor,
  type AuthResult,
  type SecondFactorMethod,
  type SecondFactorRequest,
} from './api.ts';
import { getPasskey, passkeyFailure, passkeysSupported, PASSKEYS_UNSUPPORTED } from './webauthn.ts';

/** The password step starts again, with `message` saying why (`failed`: the reason is an error, not a timeout). */
export interface Restart {
  message: string;
  failed: boolean;
}

const CODE = /^\d{6}$/;

const problemIs = (error: ApiError, slug: string) => error.type.endsWith(`/${slug}`);

/**
 * The second step of a sign-in, and of a step-up for an account that has a factor (ADR-0142): a passkey when the
 * account has one and this browser can use it, otherwise the six-digit code from an authenticator app, and a
 * recovery code as the way out for a lost device. Submitted explicitly (Enter or the button), never on the sixth
 * digit, so a code is never sent before the person means it.
 *
 * <p>The password is already right when this shows, so every failure states what happened and what to do:
 * a wrong code stays here, beside its field; a timed-out sign-in or a locked account returns to the password
 * step through `onRestart`, with the same generic words a wrong password gets.
 *
 * <p>`trustDeviceDays` above zero offers to trust this browser; it is zero for a step-up, which always asks.
 */
export function SecondFactorForm({
  methods,
  trustDeviceDays,
  onDone,
  onRestart,
  onBack,
}: {
  methods: SecondFactorMethod[];
  trustDeviceDays: number;
  onDone: (result: AuthResult) => void;
  onRestart: (why: Restart) => void;
  onBack: () => void;
}) {
  const hasTotp = methods.includes('TOTP');
  const hasRecovery = methods.includes('RECOVERY_CODE');
  const hasPasskey = methods.includes('WEBAUTHN');
  const canUsePasskey = hasPasskey && passkeysSupported();
  const secondFactor = useSecondFactor();

  const [entry, setEntry] = useState<'code' | 'recovery'>(hasTotp || !hasRecovery ? 'code' : 'recovery');
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState('');
  const [trust, setTrust] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [waitingForPasskey, setWaitingForPasskey] = useState(false);
  const field = useRef<HTMLInputElement>(null);

  const busy = secondFactor.isPending || waitingForPasskey;
  const hasEntry = hasTotp || hasRecovery;

  function clearMessages() {
    setFieldError(null);
    setFailure(null);
    setNotice(null);
  }

  function fail(error: ApiError, via: 'code' | 'recovery' | 'passkey') {
    if (error.status === 401 && problemIs(error, 'sign-in-expired')) {
      onRestart({ message: 'Your sign-in timed out. Enter your password again.', failed: false });
    } else if (error.status === 401 && problemIs(error, 'invalid-credentials')) {
      onRestart({ message: 'Invalid username or password.', failed: true });
    } else if (error.status === 403 && problemIs(error, 'reauthentication-failed')) {
      onRestart({ message: 'Enter your password again, then your code.', failed: false });
    } else if (error.status === 429) {
      setFailure('Too many attempts. Wait a few minutes, then try again.');
    } else if (problemIs(error, 'second-factor-invalid') && via !== 'passkey') {
      setFieldError(
        via === 'code'
          ? 'That code was not accepted. Enter the current code from your app, or use a recovery code.'
          : 'That recovery code was not accepted. Check it and try again; each code works once.',
      );
      field.current?.focus();
    } else if (error.status === 400) {
      setFieldError(via === 'code' ? 'Enter the 6 digits from your authenticator app.' : error.message);
      field.current?.focus();
    } else {
      setFailure(problemIs(error, 'second-factor-invalid') ? error.message : `${error.message} Try again.`);
    }
  }

  function submit(proof: SecondFactorRequest, via: 'code' | 'recovery' | 'passkey') {
    clearMessages();
    secondFactor.mutate(
      { ...proof, trustDevice: trustDeviceDays > 0 ? trust : undefined },
      { onSuccess: onDone, onError: (e) => fail(e, via) },
    );
  }

  function validCode(value: string): boolean {
    return CODE.test(value.replace(/\s/g, ''));
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (entry === 'code') {
      if (!validCode(code)) {
        setFieldError('Enter the 6 digits from your authenticator app.');
        field.current?.focus();
        return;
      }
      submit({ totpCode: code.replace(/\s/g, '') }, 'code');
    } else {
      if (!recovery.trim()) {
        setFieldError('Enter one of your recovery codes.');
        field.current?.focus();
        return;
      }
      submit({ recoveryCode: recovery.trim() }, 'recovery');
    }
  }

  async function askForPasskey() {
    clearMessages();
    setWaitingForPasskey(true);
    try {
      const credential = await getPasskey(await fetchPasskeyRequestOptions());
      if (credential === null) {
        setNotice('Passkey prompt was dismissed. Try again, or use a code.');
        return;
      }
      submit({ webauthn: credential as Record<string, unknown> }, 'passkey');
    } catch (error) {
      // An ApiError is the server's answer; anything else is the browser's.
      if (error instanceof ApiError) fail(error, 'passkey');
      else setFailure(passkeyFailure(error));
    } finally {
      setWaitingForPasskey(false);
    }
  }

  function switchTo(next: 'code' | 'recovery') {
    clearMessages();
    setEntry(next);
  }

  const intro = canUsePasskey
    ? 'Use your passkey, or enter a code, to finish signing in.'
    : entry === 'code'
      ? 'Enter the code from your authenticator app to finish signing in.'
      : 'Enter one of the recovery codes you saved when you set up two-step verification.';

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {intro}
      </Text>

      {hasPasskey ? (
        <Stack gap={6}>
          <Button
            variant={canUsePasskey ? 'filled' : 'default'}
            fullWidth
            leftSection={<IconKey size={16} aria-hidden />}
            loading={waitingForPasskey}
            disabled={!canUsePasskey || secondFactor.isPending}
            aria-describedby={canUsePasskey ? undefined : 'passkeys-unsupported'}
            autoFocus={canUsePasskey}
            onClick={() => void askForPasskey()}
          >
            Use a passkey
          </Button>
          {!canUsePasskey ? (
            <Text id="passkeys-unsupported" size="xs" c="dimmed">
              {PASSKEYS_UNSUPPORTED} Use a code instead.
            </Text>
          ) : null}
        </Stack>
      ) : null}

      {hasPasskey && hasEntry ? (
        <Divider label={entry === 'code' ? 'or enter a code' : 'or enter a recovery code'} labelPosition="center" />
      ) : null}

      {hasEntry ? (
        <form onSubmit={onSubmit} noValidate>
          <Stack gap="sm">
            {entry === 'code' ? (
              <TextInput
                key="code"
                ref={field}
                label="Code from your authenticator app"
                description="6 digits. The code changes every 30 seconds."
                value={code}
                onChange={(e) => {
                  setCode(e.currentTarget.value);
                  setFieldError(null);
                }}
                onBlur={() =>
                  code && !validCode(code) && setFieldError('Enter the 6 digits from your authenticator app.')
                }
                error={fieldError}
                inputMode="numeric"
                autoComplete="one-time-code"
                autoFocus={!canUsePasskey}
                maxLength={7}
              />
            ) : (
              <TextInput
                key="recovery"
                ref={field}
                label="Recovery code"
                description="Looks like XXXXX-XXXXX. Each code works once."
                value={recovery}
                onChange={(e) => {
                  setRecovery(e.currentTarget.value);
                  setFieldError(null);
                }}
                error={fieldError}
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                autoFocus={!canUsePasskey}
                ff="monospace"
              />
            )}

            {entry === 'code' && hasRecovery ? (
              <Anchor
                component="button"
                type="button"
                size="sm"
                w="fit-content"
                disabled={busy}
                onClick={() => switchTo('recovery')}
              >
                Use a recovery code instead
              </Anchor>
            ) : entry === 'recovery' && hasTotp ? (
              <Anchor
                component="button"
                type="button"
                size="sm"
                w="fit-content"
                disabled={busy}
                onClick={() => switchTo('code')}
              >
                Use an authenticator code instead
              </Anchor>
            ) : null}

            {trustDeviceDays > 0 ? (
              <Checkbox
                checked={trust}
                onChange={(e) => setTrust(e.currentTarget.checked)}
                label={`Trust this device for ${trustDeviceDays} ${trustDeviceDays === 1 ? 'day' : 'days'}`}
                description="Next time you sign in here, your password is enough. Confirming a sensitive action still asks for a code."
              />
            ) : null}

            <Button
              type="submit"
              variant={canUsePasskey ? 'default' : 'filled'}
              fullWidth
              loading={secondFactor.isPending}
              disabled={waitingForPasskey}
            >
              Verify
            </Button>
          </Stack>
        </form>
      ) : null}

      <div aria-live="polite">
        {waitingForPasskey ? (
          <Text size="sm" c="dimmed">
            Waiting for your passkey…
          </Text>
        ) : notice ? (
          <Text size="sm">{notice}</Text>
        ) : null}
      </div>
      {failure ? (
        <Alert color="red" variant="light" role="alert">
          {failure}
        </Alert>
      ) : null}

      <Anchor
        component="button"
        type="button"
        size="sm"
        w="fit-content"
        disabled={busy}
        onClick={onBack}
        style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
      >
        <IconArrowLeft size={14} aria-hidden />
        Back
      </Anchor>
    </Stack>
  );
}

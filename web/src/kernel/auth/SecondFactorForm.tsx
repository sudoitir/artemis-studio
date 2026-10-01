import { useState, type ReactNode } from 'react';
import { Button, Checkbox, Divider, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconArrowLeft, IconKey } from '@tabler/icons-react';

import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { ApiError } from '../api/request.ts';
import {
  fetchPasskeyRequestOptions,
  useSecondFactor,
  type AuthResult,
  type SecondFactorMethod,
  type SecondFactorRequest,
} from './api.ts';
import { getPasskey, passkeyFailure, passkeysSupported, PASSKEYS_UNSUPPORTED } from './webauthn.ts';
import classes from './SecondFactorForm.module.css';

/** The password step starts again, with `message` saying why (`failed`: the reason is an error, not a timeout). */
export interface Restart {
  message: string;
  failed: boolean;
}

const CODE = /^\d{6}$/;
const CODE_HINT = 'Enter the 6 digits from your authenticator app.';

type Entry = 'code' | 'recovery';
type Via = Entry | 'passkey';

const problemIs = (error: ApiError, slug: string) => error.type.endsWith(`/${slug}`);

const validCode = (value: string) => CODE.test(value.replace(/\s/g, ''));

/** A failure that sends the person back to the password step, or null when it stays here. */
function restartFor(error: ApiError): Restart | null {
  if (error.status === 401 && problemIs(error, 'sign-in-expired')) {
    return { message: 'Your sign-in timed out. Enter your password again.', failed: false };
  }
  if (error.status === 401 && problemIs(error, 'invalid-credentials')) {
    return { message: 'Invalid username or password. Check both and try again.', failed: true };
  }
  if (error.status === 403 && problemIs(error, 'reauthentication-failed')) {
    return { message: 'Enter your password again, then your code.', failed: false };
  }
  return null;
}

/** What a refused code says beside its field, or null when the failure is not about the field. */
function fieldErrorFor(error: ApiError, via: Via): string | null {
  if (problemIs(error, 'second-factor-invalid') && via !== 'passkey') {
    return via === 'code'
      ? 'That code was not accepted. Enter the current code from your app, or use a recovery code.'
      : 'That recovery code was not accepted. Check it and try again; each code works once.';
  }
  if (error.status === 400) return via === 'code' ? CODE_HINT : error.message;
  return null;
}

function introFor(canUsePasskey: boolean, entry: Entry): string {
  if (canUsePasskey) return 'Use your passkey, or enter a code, to finish signing in.';
  return entry === 'code'
    ? 'Enter the code from your authenticator app to finish signing in.'
    : 'Enter one of the recovery codes you saved when you set up two-step verification.';
}

/**
 * The second step of a sign-in, and of a step-up for an account that has a factor (ADR-0143): a passkey when the
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
}: Readonly<{
  methods: SecondFactorMethod[];
  trustDeviceDays: number;
  onDone: (result: AuthResult) => void;
  onRestart: (why: Restart) => void;
  onBack: () => void;
}>) {
  const hasTotp = methods.includes('TOTP');
  const hasRecovery = methods.includes('RECOVERY_CODE');
  const hasPasskey = methods.includes('WEBAUTHN');
  const canUsePasskey = hasPasskey && passkeysSupported();
  const [entry, setEntry] = useState<Entry>(hasTotp || !hasRecovery ? 'code' : 'recovery');
  const attempt = useAttempt(entry, trustDeviceDays, onDone, onRestart);
  const passkey = usePasskeyAttempt(attempt);

  const busy = attempt.pending || passkey.waiting;
  const hasEntry = hasTotp || hasRecovery;

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {introFor(canUsePasskey, entry)}
      </Text>

      {hasPasskey ? (
        <PasskeyButton
          canUse={canUsePasskey}
          waiting={passkey.waiting}
          disabled={attempt.pending}
          onClick={() => void passkey.ask()}
        />
      ) : null}

      {hasPasskey && hasEntry ? (
        <Divider label={entry === 'code' ? 'or enter a code' : 'or enter a recovery code'} labelPosition="center" />
      ) : null}

      {hasEntry ? (
        <EntryForm
          entry={entry}
          hasTotp={hasTotp}
          hasRecovery={hasRecovery}
          canUsePasskey={canUsePasskey}
          trustDeviceDays={trustDeviceDays}
          attempt={attempt}
          busy={busy}
          waiting={passkey.waiting}
          onSwitch={(next) => {
            attempt.clearMessages();
            setEntry(next);
          }}
        />
      ) : null}

      <div aria-live="polite">
        {passkey.waiting ? (
          <Text size="sm" c="dimmed">
            Waiting for your passkey…
          </Text>
        ) : null}
        {!passkey.waiting && attempt.notice ? <Text size="sm">{attempt.notice}</Text> : null}
      </div>
      {attempt.failure ? (
        <Text size="sm" role="alert" className={classes.failure}>
          {attempt.failure}
        </Text>
      ) : null}

      <Button
        variant="subtle"
        size="compact-sm"
        w="fit-content"
        disabled={busy}
        onClick={onBack}
        leftSection={<IconArrowLeft size="0.875rem" aria-hidden />}
      >
        Back
      </Button>
    </Stack>
  );
}

/** The submission of a proof and what its refusal says: beside the field, above the form, or back at the password. */
function useAttempt(
  entry: Entry,
  trustDeviceDays: number,
  onDone: (result: AuthResult) => void,
  onRestart: (why: Restart) => void,
) {
  const secondFactor = useSecondFactor();
  const form = useForm({
    initialValues: { code: '', recovery: '', trust: false },
    validateInputOnBlur: true,
    // Only the entry in use is checked; the other field is not on screen.
    validate: {
      code: (v) => (entry === 'code' && !validCode(v) ? CODE_HINT : null),
      recovery: (v) => (entry === 'recovery' && !v.trim() ? 'Enter one of your recovery codes.' : null),
    },
  });
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function clearMessages() {
    form.clearErrors();
    setFailure(null);
    setNotice(null);
  }

  function refuseField(message: string) {
    form.setErrors({ [entry]: message });
    form.getInputNode(entry)?.focus();
  }

  function fail(error: ApiError, via: Via) {
    const restart = restartFor(error);
    if (restart) {
      onRestart(restart);
      return;
    }
    if (error.status === 429) {
      setFailure('Too many attempts. Wait a few minutes, then try again.');
      return;
    }
    const message = fieldErrorFor(error, via);
    if (message) refuseField(message);
    else setFailure(problemIs(error, 'second-factor-invalid') ? error.message : `${error.message} Try again.`);
  }

  function submit(proof: SecondFactorRequest, via: Via) {
    clearMessages();
    secondFactor.mutate(
      { ...proof, trustDevice: trustDeviceDays > 0 ? form.values.trust : undefined },
      { onSuccess: onDone, onError: (e) => fail(e, via) },
    );
  }

  return {
    pending: secondFactor.isPending,
    form,
    failure,
    setFailure,
    notice,
    setNotice,
    clearMessages,
    refuseField,
    fail,
    submit,
  };
}

type Attempt = ReturnType<typeof useAttempt>;

/** Asking the browser for a passkey and sending what it returns. */
function usePasskeyAttempt(attempt: Attempt) {
  const [waiting, setWaiting] = useState(false);

  async function ask() {
    attempt.clearMessages();
    setWaiting(true);
    try {
      const credential = await getPasskey(await fetchPasskeyRequestOptions());
      if (credential === null) {
        attempt.setNotice('Passkey prompt was dismissed. Try again, or use a code.');
        return;
      }
      attempt.submit({ webauthn: credential as Record<string, unknown> }, 'passkey');
    } catch (error) {
      // An ApiError is the server's answer; anything else is the browser's.
      if (error instanceof ApiError) attempt.fail(error, 'passkey');
      else attempt.setFailure(passkeyFailure(error));
    } finally {
      setWaiting(false);
    }
  }

  return { waiting, ask };
}

/** The code or recovery code entry: its field, the way to the other entry, trusting the device, and Verify. */
function EntryForm({
  entry,
  hasTotp,
  hasRecovery,
  canUsePasskey,
  trustDeviceDays,
  attempt,
  busy,
  waiting,
  onSwitch,
}: Readonly<{
  entry: Entry;
  hasTotp: boolean;
  hasRecovery: boolean;
  canUsePasskey: boolean;
  trustDeviceDays: number;
  attempt: Attempt;
  busy: boolean;
  waiting: boolean;
  onSwitch: (next: Entry) => void;
}>) {
  const { form } = attempt;
  const onSubmit = form.onSubmit(({ code, recovery }) => {
    if (entry === 'code') attempt.submit({ totpCode: code.replace(/\s/g, '') }, 'code');
    else attempt.submit({ recoveryCode: recovery.trim() }, 'recovery');
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form onSubmit={onSubmit} noValidate>
      <Stack gap="sm">
        <EntryField entry={entry} inputProps={form.getInputProps(entry)} autoFocus={!canUsePasskey} />

        {entry === 'code' && hasRecovery ? (
          <SwitchLink busy={busy} onClick={() => onSwitch('recovery')}>
            Use a recovery code instead
          </SwitchLink>
        ) : null}
        {entry === 'recovery' && hasTotp ? (
          <SwitchLink busy={busy} onClick={() => onSwitch('code')}>
            Use an authenticator code instead
          </SwitchLink>
        ) : null}

        {trustDeviceDays > 0 ? (
          <Checkbox
            {...form.getInputProps('trust', { type: 'checkbox' })}
            label={`Trust this device for ${trustDeviceDays} ${trustDeviceDays === 1 ? 'day' : 'days'}`}
            description="Next time you sign in here, your password is enough. Confirming a sensitive action still asks for a code."
          />
        ) : null}

        <Button
          type="submit"
          variant={canUsePasskey ? 'default' : 'filled'}
          fullWidth
          loading={attempt.pending}
          disabled={waiting}
        >
          Verify
        </Button>
      </Stack>
    </form>
  );
}

function PasskeyButton({
  canUse,
  waiting,
  disabled,
  onClick,
}: Readonly<{ canUse: boolean; waiting: boolean; disabled: boolean; onClick: () => void }>) {
  return (
    <Stack gap={6}>
      <Button
        variant={canUse ? 'filled' : 'default'}
        fullWidth
        leftSection={<IconKey size="1rem" aria-hidden />}
        loading={waiting}
        disabled={!canUse || disabled}
        aria-describedby={canUse ? undefined : 'passkeys-unsupported'}
        autoFocus={canUse}
        onClick={onClick}
      >
        Use a passkey
      </Button>
      {canUse ? null : (
        <Text id="passkeys-unsupported" size="xs" c="dimmed">
          {PASSKEYS_UNSUPPORTED} Use a code instead.
        </Text>
      )}
    </Stack>
  );
}

/** The field of the entry in use: the authenticator code, or a recovery code. */
function EntryField({
  entry,
  inputProps,
  autoFocus,
}: Readonly<{
  entry: Entry;
  inputProps: ReturnType<ReturnType<typeof useAttempt>['form']['getInputProps']>;
  autoFocus: boolean;
}>) {
  if (entry === 'code') {
    return (
      <TextInput
        key="code"
        label="Code from your authenticator app"
        description="6 digits. The code changes every 30 seconds."
        {...inputProps}
        inputMode="numeric"
        autoComplete="one-time-code"
        autoFocus={autoFocus}
        maxLength={7}
      />
    );
  }
  return (
    <TextInput
      key="recovery"
      label="Recovery code"
      description="Looks like XXXXX-XXXXX. Each code works once."
      {...inputProps}
      autoComplete="off"
      autoCapitalize="characters"
      spellCheck={false}
      autoFocus={autoFocus}
      styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
    />
  );
}

function SwitchLink({
  busy,
  onClick,
  children,
}: Readonly<{ busy: boolean; onClick: () => void; children: ReactNode }>) {
  return (
    <Button variant="subtle" size="compact-sm" w="fit-content" disabled={busy} onClick={onClick}>
      {children}
    </Button>
  );
}

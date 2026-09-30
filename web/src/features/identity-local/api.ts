import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type ChangePasswordRequest = Schemas['ChangePasswordRequest'];
export type MfaStatusView = Schemas['MfaStatusView'];
export type PasskeyView = Schemas['PasskeyView'];
export type PasskeyRegisteredView = Schemas['PasskeyRegisteredView'];
export type TotpEnrolmentView = Schemas['TotpEnrolmentView'];
export type TotpConfirmedView = Schemas['TotpConfirmedView'];
export type TrustedDeviceView = Schemas['TrustedDeviceView'];

export const keys = {
  me: ['auth', 'me'] as const,
  mfa: ['auth', 'mfa'] as const,
};

export function useChangePassword() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, ChangePasswordRequest>({
    // Its variables are both passwords; the cache forgets them with the screen.
    gcTime: 0,
    mutationFn: (body) =>
      request<void>('/auth/password', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    // The server clears `mustChangePassword` and re-authenticates the same
    // session, but the cached `me` still says the account is locked — without
    // this refetch RootLayout bounces the user straight back to /change-password.
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.me }),
  });
}

/** Where the caller's second factors stand. Also readable while enrolment is all the session may do. */
export function useMfaStatus(enabled = true): UseQueryResult<MfaStatusView, ApiError> {
  return useQuery({
    queryKey: keys.mfa,
    queryFn: () => request<MfaStatusView>('/auth/mfa'),
    enabled,
  });
}

/**
 * What a factor mutation returns can be a secret (an authenticator key, recovery codes) that is shown once, so the
 * cache forgets the result the moment nothing observes it: `gcTime` 0, where the default keeps it for minutes.
 * A screen that stays mounted after showing one calls `reset()` once it has taken what it needs.
 */
const FORGET_AT_ONCE = { gcTime: 0 } as const;

/**
 * A change to the caller's factors refreshes the status, and `me` too: finishing an enrolment or removing a factor
 * re-establishes the session under a new id, and what `me` says (`secondFactorEnrolmentRequired`, when the session last
 * signed in) has changed with it. Resolves once both are fresh, so a caller may move on without being sent back.
 */
function useFactorMutation<TVars, TResult>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation<TResult, ApiError, TVars>({
    ...FORGET_AT_ONCE,
    mutationFn: fn,
    onSuccess: () =>
      Promise.all([qc.invalidateQueries({ queryKey: keys.mfa }), qc.invalidateQueries({ queryKey: keys.me })]),
  });
}

/** Begins an authenticator app; nothing is active until it is confirmed, so the old one keeps working. */
export function useStartTotp() {
  return useMutation<TotpEnrolmentView, ApiError, void>({
    ...FORGET_AT_ONCE,
    mutationFn: () => request<TotpEnrolmentView>('/auth/mfa/totp', { method: 'POST' }),
  });
}

export function useConfirmTotp() {
  return useFactorMutation((code: string) =>
    request<TotpConfirmedView>('/auth/mfa/totp/confirm', { method: 'POST', body: JSON.stringify({ code }) }),
  );
}

export function useRemoveTotp() {
  return useFactorMutation<void, void>(() => request<void>('/auth/mfa/totp', { method: 'DELETE' }));
}

/** The options a browser creates a passkey with, as `parseCreationOptionsFromJSON` takes them. */
export function fetchPasskeyCreationOptions() {
  return request<PublicKeyCredentialCreationOptionsJSON>('/auth/mfa/webauthn/options', { method: 'POST' });
}

export function useRegisterPasskey() {
  return useFactorMutation((body: { label: string; credential: object }) =>
    request<PasskeyRegisteredView>('/auth/mfa/webauthn', { method: 'POST', body: JSON.stringify(body) }),
  );
}

export function useRemovePasskey() {
  return useFactorMutation((id: string) =>
    request<void>(`/auth/mfa/webauthn/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  );
}

/** New recovery codes; the old ones stop working. */
export function useRegenerateRecoveryCodes() {
  return useFactorMutation<void, Schemas['RecoveryCodesView']>(() =>
    request<Schemas['RecoveryCodesView']>('/auth/mfa/recovery-codes', { method: 'POST' }),
  );
}

export function useRevokeTrustedDevice() {
  return useFactorMutation((id: string) => request<void>(`/auth/mfa/trusted-devices/${id}`, { method: 'DELETE' }));
}

export function useRevokeAllTrustedDevices() {
  return useFactorMutation<void, void>(() => request<void>('/auth/mfa/trusted-devices', { method: 'DELETE' }));
}

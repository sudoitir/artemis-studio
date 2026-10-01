import type { UserView } from './api.ts';

const FACTOR_WORDS = { TOTP: 'Authenticator app', WEBAUTHN: 'Passkey' } as const;

/** A user's registered second factors, in words. */
export const factorWords = (user: UserView): string[] =>
  user.secondFactors.flatMap((f) => (f in FACTOR_WORDS ? [FACTOR_WORDS[f as keyof typeof FACTOR_WORDS]] : []));

/** What a user's second step is, in words. */
export function twoStepText(user: UserView): string {
  const factors = factorWords(user);
  if (!user.passwordAccount) return 'Managed by their identity provider';
  if (factors.length === 0) return user.secondFactorRequired ? 'Required, not set up' : 'Not set up';
  return factors.join(', ');
}

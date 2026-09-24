import { useMe } from '../../kernel/auth/api.ts';

/** Margin so a confirmation typed just inside the window does not reach the server just outside it. */
const MARGIN_MS = 30_000;

/** Whether this session signed in or confirmed it recently enough for a plugin action (ADR-0103). */
export function useFreshSignIn(): boolean {
  const reauth = useMe().data?.reauthentication;
  if (!reauth?.authenticatedAt) return false;
  const age = Date.now() - new Date(reauth.authenticatedAt).getTime();
  return age < reauth.windowSeconds * 1000 - MARGIN_MS;
}

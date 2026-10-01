import { absoluteLabel, serverNow } from '../../kernel/time/time.ts';
import type { TokenView } from './api.ts';

export type TokenState = 'Active' | 'Expired' | 'Revoked';

/** Whether a key can still authenticate, in words: revoked wins over expired. */
export function tokenState(token: TokenView): TokenState {
  if (token.revokedAt) return 'Revoked';
  if (Date.parse(token.expiresAt) < serverNow()) return 'Expired';
  return 'Active';
}

/** A server instant in the display zone, or `fallback` when there is none. */
export function instantLabel(iso: string | null | undefined, fallback = 'never'): string {
  return iso ? absoluteLabel(iso) : fallback;
}

/** The MCP tools a key is limited to, or that it may call every one its permissions allow. */
export function toolsLabel(token: TokenView): string {
  return token.mcpTools.length === 0 ? 'Every tool' : token.mcpTools.join(', ');
}

/** The note under a rotated key's expiry while its old secret still works, or null. */
export function overlapNote(token: TokenView): string | null {
  return token.previousValidUntil && Date.parse(token.previousValidUntil) > serverNow()
    ? `Old secret works until ${instantLabel(token.previousValidUntil)}`
    : null;
}

/**
 * Passkeys through the browser's own JSON helpers (`PublicKeyCredential.parse*OptionsFromJSON` and `toJSON()`),
 * with no WebAuthn library: the server speaks the JSON forms, and a browser that has the API has the helpers.
 */

/** Whether this browser can take the server's passkey options as they are sent. */
export function passkeysSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.PublicKeyCredential === 'function' &&
    typeof PublicKeyCredential.parseRequestOptionsFromJSON === 'function' &&
    typeof PublicKeyCredential.parseCreationOptionsFromJSON === 'function' &&
    !!navigator.credentials
  );
}

/** Said where a passkey cannot be used, so the reason and the alternative are one sentence. */
export const PASSKEYS_UNSUPPORTED = 'This browser cannot use passkeys. Update it, or use another browser.';

/** Why passkeys cannot be used here, or null. Until the server has said (`undefined`), unknown is not unavailable. */
export function passkeyUnavailableReason(
  webauthn: { available: boolean; reason?: string | null } | undefined,
): string | null {
  if (!passkeysSupported()) return PASSKEYS_UNSUPPORTED;
  if (webauthn && !webauthn.available) return webauthn.reason ?? 'Passkeys are not available on this installation.';
  return null;
}

/** The person closed the browser's passkey prompt, or it timed out: not a failure to report as one. */
function dismissed(error: unknown): boolean {
  return error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'AbortError');
}

/** Asks for a passkey to answer the options with. `null` when the prompt was dismissed. */
export async function getPasskey(options: PublicKeyCredentialRequestOptionsJSON): Promise<object | null> {
  try {
    const credential = await navigator.credentials.get({
      publicKey: PublicKeyCredential.parseRequestOptionsFromJSON(options),
    });
    return credential ? (credential as PublicKeyCredential).toJSON() : null;
  } catch (error) {
    if (dismissed(error)) return null;
    throw error;
  }
}

/** Asks the browser to create a passkey. `null` when the prompt was dismissed. */
export async function createPasskey(options: PublicKeyCredentialCreationOptionsJSON): Promise<object | null> {
  try {
    const credential = await navigator.credentials.create({
      publicKey: PublicKeyCredential.parseCreationOptionsFromJSON(options),
    });
    return credential ? (credential as PublicKeyCredential).toJSON() : null;
  } catch (error) {
    if (dismissed(error)) return null;
    throw error;
  }
}

/** What went wrong in the browser, and what to do, for an error that is not a dismissed prompt. */
export function passkeyFailure(error: unknown): string {
  if (error instanceof DOMException && error.name === 'InvalidStateError') {
    return 'This device already holds a passkey for your account. Use a different device, or remove the old passkey first.';
  }
  const why = error instanceof Error && error.message ? ` (${error.message})` : '';
  return `The browser could not use your passkey${why}. Try again, or use another method.`;
}

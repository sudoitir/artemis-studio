import { vi } from 'vitest';

/** What `navigator.credentials` answers with: the browser's credential, which serialises with `toJSON()`. */
export const credential = (json: object) => ({ toJSON: () => json });

/**
 * A browser that can do passkeys: `PublicKeyCredential`'s JSON helpers and `navigator.credentials`, which jsdom has
 * neither of. `parse*OptionsFromJSON` tag what they were given so a test can see the options went through them.
 */
export function stubPasskeys(credentials: { get?: () => Promise<unknown>; create?: () => Promise<unknown> }) {
  vi.stubGlobal(
    'PublicKeyCredential',
    Object.assign(function PublicKeyCredential() {}, {
      parseRequestOptionsFromJSON: vi.fn((options: object) => ({ ...options, parsed: 'request' })),
      parseCreationOptionsFromJSON: vi.fn((options: object) => ({ ...options, parsed: 'creation' })),
    }),
  );
  Object.defineProperty(navigator, 'credentials', {
    configurable: true,
    value: { get: vi.fn(credentials.get), create: vi.fn(credentials.create) },
  });
}

export function unstubPasskeys() {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(navigator, 'credentials');
}

/** The prompt was closed, or timed out: the browser rejects with this. */
export const dismissedPrompt = () =>
  new DOMException('The operation either timed out or was not allowed.', 'NotAllowedError');

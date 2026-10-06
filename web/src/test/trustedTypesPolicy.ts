/**
 * The Trusted Types part of Studio's Content-Security-Policy, as the server sends it (ADR-0168). The
 * browser tests are served with it, so a sink that takes a plain string fails them as it would fail
 * for a viewer; `scripts/trusted-types-policy.test.ts` keeps it equal to the server's.
 */
export const TRUSTED_TYPES_DIRECTIVES =
  "require-trusted-types-for 'script'; trusted-types default dompurify studio#worker";

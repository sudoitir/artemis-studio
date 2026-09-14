// The SQL feature's public surface for other features (ADR-0074): what message capture covers.
export { useIndexSubscriptions, type SqlIndexSubscriptionView } from './api.ts';
export { CaptureHint } from './CaptureHint.tsx';
export { uncapturedAddresses } from './captureCoverage.ts';

import { ApiError } from './request.ts';

/** How many times a failed query is tried again. */
const ATTEMPTS = 3;

/**
 * Whether a failed query is worth trying again: a network failure, a timeout, a server error or a
 * rate limit may pass, while any other client error (a missing run, a refused permission, a bad
 * request) will fail the same way every time, and trying again only delays the answer.
 */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= ATTEMPTS) return false;
  if (!(error instanceof ApiError)) return true;
  return error.status >= 500 || error.status === 408 || error.status === 429;
}

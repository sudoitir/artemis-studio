import { describe, expect, it } from 'vitest';

import { ApiError } from './request.ts';
import { shouldRetry } from './retry.ts';

const api = (status: number) => new ApiError(status, { title: 'Refused' });

describe('shouldRetry', () => {
  it('tries a failed network request again, up to three times', () => {
    expect(shouldRetry(0, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetry(2, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetry(3, new TypeError('Failed to fetch'))).toBe(false);
  });

  it('tries a server error, a timeout and a rate limit again', () => {
    for (const status of [500, 502, 503, 408, 429]) expect(shouldRetry(0, api(status))).toBe(true);
  });

  it('does not try a client error again', () => {
    for (const status of [400, 401, 403, 404, 409, 422]) expect(shouldRetry(0, api(status))).toBe(false);
  });
});

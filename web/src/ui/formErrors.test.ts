import { describe, expect, it } from 'vitest';

import { focusFirstInvalid, serverFieldErrors } from './formErrors.ts';

describe('focusFirstInvalid', () => {
  it('focuses the first invalid field in reading order, not in the order the errors were found', () => {
    document.body.innerHTML = '<input data-path="first" /><input data-path="second" />';
    const byPath = (path: string) => document.querySelector<HTMLElement>(`[data-path="${path}"]`);

    focusFirstInvalid(byPath)({ second: 'wrong', first: 'wrong' });

    expect(document.activeElement).toBe(byPath('first'));
  });

  it('does nothing when no invalid field is on the page', () => {
    document.body.innerHTML = '<input data-path="first" />';

    expect(() => focusFirstInvalid(() => null)({ gone: 'wrong' })).not.toThrow();
    expect(document.activeElement).toBe(document.body);
  });
});

describe('serverFieldErrors', () => {
  const error = {
    fieldErrors: [
      { field: 'name', message: 'must not be blank' },
      { field: 'elsewhere', message: 'not on this form' },
    ],
  };

  it('keeps the errors that name a field the form has', () => {
    expect(serverFieldErrors(error, ['name', 'address'])).toEqual({ name: 'must not be blank' });
  });

  it('is empty for an error that carries no field errors', () => {
    expect(serverFieldErrors(new Error('boom'), ['name'])).toEqual({});
    expect(serverFieldErrors(undefined, ['name'])).toEqual({});
  });
});

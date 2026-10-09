import { useEffect } from 'react';

/**
 * A pair of fields that go together, such as a username and its password: both, or neither. The message
 * goes on the member that is missing, which is where the operator has to act, and says what to do about it.
 * Returns the problems by field name, empty when the pair is complete or both are empty.
 */
export function pairProblems(
  first: { name: string; value: string; missing: string },
  second: { name: string; value: string; missing: string },
): Record<string, string> {
  if (Boolean(first.value) === Boolean(second.value)) return {};
  return first.value ? { [second.name]: second.missing } : { [first.name]: first.missing };
}

/**
 * Keeps a pair's messages true while either member is edited. Validation on blur checks only the field
 * that was left, so filling in the second member would leave the first one's message on screen; this
 * checks again each member that shows one, clearing it once the pair is complete and moving it to the
 * member that is missing when it is not.
 */
export function useRevalidatePairs(
  form: {
    errors: Record<string, unknown>;
    values: object;
    validateField: (path: never) => { hasError: boolean };
    clearFieldError: (path: never) => void;
  },
  pairs: [string, string][],
) {
  const { errors, values, validateField, clearFieldError } = form;
  const at = (name: string) => String((values as Record<string, unknown>)[name]);
  const signature = pairs.map(([a, b]) => `${at(a)}\u0000${at(b)}`).join('\u0001');
  useEffect(() => {
    for (const [a, b] of pairs) {
      for (const [mine, other] of [
        [a, b],
        [b, a],
      ]) {
        if (!errors[mine] && !errors[other]) continue;
        // The member that no longer has a problem is cleared; one that still has it is shown again.
        const problem = validateField(mine as never);
        if (!problem.hasError) clearFieldError(mine as never);
      }
    }
    // Only an edit of a member re-checks; the errors it changes must not trigger another pass.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);
}

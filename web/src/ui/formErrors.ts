/**
 * The second argument of `form.onSubmit`: on a rejected submit, move focus to the first invalid
 * field in reading order, so the operator lands on what to fix rather than hunting for it.
 */
export function focusFirstInvalid(getInputNode: (path: string) => HTMLElement | null) {
  return (errors: Record<string, unknown>) => {
    const nodes = Object.keys(errors)
      .map((path) => getInputNode(path))
      .filter((node): node is HTMLElement => node !== null);
    nodes.sort((a, b) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1));
    nodes[0]?.focus();
  };
}

/**
 * The server's field errors that name a field this form has, ready for `form.setErrors`. An error for
 * a field the form does not show is left out, because a message with no field to sit beside is lost.
 */
export function serverFieldErrors(error: unknown, fields: readonly string[]): Record<string, string> {
  const given = typeof error === 'object' && error !== null && 'fieldErrors' in error ? error.fieldErrors : undefined;
  const mapped: Record<string, string> = {};
  if (!Array.isArray(given)) return mapped;
  for (const entry of given) {
    if (typeof entry?.field === 'string' && typeof entry?.message === 'string' && fields.includes(entry.field)) {
      mapped[entry.field] = entry.message;
    }
  }
  return mapped;
}

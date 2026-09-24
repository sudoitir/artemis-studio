/**
 * The printable key an event stands for, in the Latin layout the shortcuts are named in. On a
 * non-Latin layout (Persian, Russian, Greek) `event.key` is that script's letter, so the physical
 * key decides instead: an operator does not switch layout to press `g q`.
 */
export function latinKey(event: KeyboardEvent): string {
  if (/^[\x20-\x7e]$/.test(event.key)) return event.key;
  const code = event.code;
  if (/^Key[A-Z]$/.test(code)) {
    const letter = code.slice(3).toLowerCase();
    return event.shiftKey ? letter.toUpperCase() : letter;
  }
  if (code === 'Slash') return event.shiftKey ? '?' : '/';
  return event.key;
}

/**
 * Whether a key press belongs to something else and must not be taken as a shortcut (ADR-0109):
 * typing into a field or an editor, a modified key, an IME composition, a key already handled, and
 * anything inside a dialog or a menu — a confirmation is never navigated away from by a stray key.
 */
export function ignoredForShortcuts(event: KeyboardEvent): boolean {
  if (event.defaultPrevented || event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return true;
  const target = event.target;
  if (!(target instanceof Element)) return false;
  if (target.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"], [role="textbox"]')) {
    return true;
  }
  return Boolean(target.closest('[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]'));
}

/**
 * A `restoreFocus` for a dialog opened by a control inside a grid row: back to that control when it
 * is still there, otherwise to the grid's tab stop — the row may have left the grid because the
 * action succeeded (a closed connection is no longer listed), and focus should not fall to <body>.
 */
export function focusBack(trigger: HTMLElement): () => void {
  const grid = trigger.closest<HTMLElement>('[role="grid"]');
  return () => {
    if (trigger.isConnected) {
      trigger.focus();
      return;
    }
    grid?.querySelector<HTMLElement>('[tabindex="0"]')?.focus();
  };
}

import { act, fireEvent } from '@testing-library/react';

/** Longer than the shortened hold `setup.ts` sets, so the press completes. */
const HOLD_FOR_MS = 150;

/** Presses and holds a given button with the mouse for the length a hold takes. */
export async function holdButton(button: HTMLElement) {
  fireEvent.mouseDown(button);
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, HOLD_FOR_MS));
  });
  fireEvent.mouseUp(button);
}

/** The same hold from the keyboard: Space held down on the focused button, then released. */
export async function holdByKeyboard(button: HTMLElement, key = ' ') {
  button.focus();
  fireEvent.keyDown(button, { key });
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, HOLD_FOR_MS));
  });
  fireEvent.keyUp(button, { key });
}

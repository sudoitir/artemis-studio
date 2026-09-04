import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { cleanup } from '@testing-library/react';
import { setupServer } from 'msw/node';

/**
 * Shared MSW network mock (ADR-0024). Tests add per-case handlers with
 * `server.use(http.get(...))`; anything unhandled is a hard error so a missing
 * mock fails loudly instead of hanging on a real fetch.
 */
export const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());

// jsdom gaps that Mantine and @tanstack/react-virtual reach for. jsdom 30 has a
// matchMedia that throws "Not implemented", so replace it outright.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  configurable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }),
});

// Fires the callback once on observe() with a non-zero rect — @tanstack/react-virtual
// needs at least one measurement or it renders no rows under jsdom.
class ResizeObserverStub {
  #cb: ResizeObserverCallback;
  constructor(cb: ResizeObserverCallback) {
    this.#cb = cb;
  }
  observe(target: Element) {
    this.#cb(
      [{ target, contentRect: target.getBoundingClientRect() } as ResizeObserverEntry],
      this as unknown as ResizeObserver,
    );
  }
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;

window.HTMLElement.prototype.scrollIntoView ??= () => {};

// @tanstack/react-virtual sizes the scroll element from offsetWidth/offsetHeight,
// which jsdom always reports as 0 — leaving the virtualizer with no rows. Give
// every element a viewport-sized box so getVirtualItems() yields rows.
for (const [prop, value] of [
  ['offsetWidth', 1000],
  ['offsetHeight', 800],
] as const) {
  Object.defineProperty(window.HTMLElement.prototype, prop, {
    configurable: true,
    get() {
      return value;
    },
  });
}

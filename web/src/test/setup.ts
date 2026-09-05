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

// jsdom has no CSS Font Loading API; Mantine's autosizing Textarea
// (react-textarea-autosize) reaches for `document.fonts.addEventListener` to
// re-measure once a web font finishes loading.
if (!document.fonts) {
  Object.defineProperty(document, 'fonts', {
    configurable: true,
    value: { addEventListener: () => {}, removeEventListener: () => {} },
  });
}

// jsdom has no EventSource. This stub records every open instance and lets a test
// push a named frame with `EventSourceStub.emit('events', data)`.
type Listener = (e: MessageEvent) => void;
class EventSourceStub {
  static instances: EventSourceStub[] = [];
  url: string;
  readyState = 1;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  listeners = new Map<string, Set<Listener>>();
  constructor(url: string) {
    this.url = url;
    EventSourceStub.instances.push(this);
    queueMicrotask(() => this.onopen?.());
  }
  addEventListener(type: string, cb: Listener) {
    let set = this.listeners.get(type);
    if (!set) {
      set = new Set();
      this.listeners.set(type, set);
    }
    set.add(cb);
  }
  removeEventListener(type: string, cb: Listener) {
    this.listeners.get(type)?.delete(cb);
  }
  close() {
    this.readyState = 2;
  }
  /** Test helper: deliver a named frame to every open stub (or one, by index). */
  static emit(type: string, data: unknown, index?: number) {
    const targets =
      index === undefined ? EventSourceStub.instances : [EventSourceStub.instances[index]];
    for (const es of targets) {
      const frame = {
        data: typeof data === 'string' ? data : JSON.stringify(data),
      } as MessageEvent;
      es?.listeners.get(type)?.forEach((cb) => cb(frame));
    }
  }
  static reset() {
    EventSourceStub.instances = [];
  }
}
window.EventSource = EventSourceStub as unknown as typeof EventSource;
afterEach(() => EventSourceStub.reset());
export { EventSourceStub };

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

// jsdom's getBoundingClientRect always reports a zero-size box. Recharts'
// ResponsiveContainer (via @mantine/charts) sizes itself from the
// ResizeObserver entry's contentRect, not offsetWidth/offsetHeight, so a
// chart renders nothing under jsdom without this — matching the same
// viewport-sized stub above.
window.HTMLElement.prototype.getBoundingClientRect = () =>
  ({
    width: 1000,
    height: 800,
    top: 0,
    left: 0,
    right: 1000,
    bottom: 800,
    x: 0,
    y: 0,
    toJSON() {},
  }) as DOMRect;

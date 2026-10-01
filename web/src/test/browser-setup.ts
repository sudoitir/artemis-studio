import '@testing-library/jest-dom/vitest';
import { afterEach, beforeAll } from 'vitest';
import { cleanup } from '@testing-library/react';

// The same style sheets and fonts the application loads (main.tsx), so a browser test lays out what a
// viewer sees. Nothing is wrapped globally: a test renders its own providers and does its own
// data passing, and there is no network mock because there is no network (ADR-0164).
import '@fontsource-variable/atkinson-hyperlegible-next/index.css';
import '@fontsource-variable/atkinson-hyperlegible-mono/index.css';
import '@mantine/core/styles.css';
import '../theme.css';

// A face downloads when text first uses it, so `document.fonts.ready` alone would resolve before the
// bundled typefaces had been asked for. Ask for the weights the console draws, then wait.
beforeAll(async () => {
  // Testing Library turns React's `act` environment on, and React then warns about every update the
  // browser makes by itself (a resize observer, a frame). Nothing here is driven by `act`.
  Reflect.set(globalThis, 'IS_REACT_ACT_ENVIRONMENT', false);
  await Promise.all(
    ['400', '600'].flatMap((weight) =>
      ['Atkinson Hyperlegible Next Variable', 'Atkinson Hyperlegible Mono Variable'].map((family) =>
        document.fonts.load(`${weight} 1rem "${family}"`, 'Aa0'),
      ),
    ),
  );
  await document.fonts.ready;
});

afterEach(cleanup);

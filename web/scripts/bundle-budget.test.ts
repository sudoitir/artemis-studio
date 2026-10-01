import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { budgetProblems, ENTRY_LIMIT, HEAVY } from './bundle-budget.ts';

/** A build in a temporary folder: the stub `index.html` loads, the real entry it imports, and what it preloads. */
let dist: string;
beforeEach(() => {
  dist = mkdtempSync(join(tmpdir(), 'bundle-budget-'));
  mkdirSync(join(dist, 'assets'));
});
afterEach(() => rmSync(dist, { recursive: true, force: true }));

function build({ entry = 'x', preloaded = 'x' }: { entry?: string; preloaded?: string } = {}) {
  writeFileSync(
    join(dist, 'index.html'),
    `<html><head>
       <script type="module" crossorigin src="/assets/stub.js"></script>
       <link rel="modulepreload" crossorigin href="/assets/shared.js">
       <link rel="preload" as="font" href="/assets/font.woff2">
     </head></html>`,
  );
  writeFileSync(join(dist, 'assets/stub.js'), 'Promise.resolve().then(() => import("./index.js"));');
  writeFileSync(join(dist, 'assets/index.js'), entry);
  writeFileSync(join(dist, 'assets/shared.js'), preloaded);
}

describe('the bundle budget', () => {
  it('passes a small entry and preloads of no heavy library', () => {
    build();
    expect(budgetProblems(dist)).toEqual([]);
  });

  it('fails an entry chunk over the limit, found through the stub index.html loads', () => {
    build({ entry: 'x'.repeat(ENTRY_LIMIT + 1) });
    expect(budgetProblems(dist)).toEqual([expect.stringContaining('assets/index.js')]);
  });

  it.each(Object.entries(HEAVY))('fails a preloaded chunk that holds %s', (library, signature) => {
    build({ preloaded: `var a = "${signature}";` });
    expect(budgetProblems(dist)).toEqual([`index.html preloads assets/shared.js, which holds ${library}`]);
  });
});

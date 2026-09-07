/**
 * Capture the README screenshots against a running Studio.
 *
 *   ADMIN_PASSWORD=… npm --prefix web run shots      (or: just shots)
 *
 * Point it at whatever `just demo` left running. It signs in as a real operator
 * would (`session.ts`), waits for each view's own evidence of having loaded —
 * never a fixed sleep, which produces a screenshot of a skeleton often enough to
 * matter — and writes over `docs/img/*.png`.
 *
 * The viewport is fixed so the images crop identically in the README table, and
 * `deviceScaleFactor: 2` so they stay legible when GitHub scales them down.
 *
 * The moving demo is `demo.ts`.
 */
import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, password, signIn, streamLive } from './session.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../../docs/img');

password(); // fail before launching a browser if it is missing

// A query with one pushdown predicate and one target wildcard: the plan strip
// then has something to classify, which is the part of this screen worth showing.
const SQL = `SELECT * FROM "ORDERS.*"
WHERE props.tenant = 'acme'
ORDER BY timestamp DESC
LIMIT 200`;

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: 'dark',
  });

  const clusterId = await signIn(page);

  const shots: Array<{
    file: string;
    path: string;
    height?: number;
    /** Drives the view into the state worth photographing, before `ready`. */
    before?: () => Promise<unknown>;
    ready: () => Promise<unknown>;
  }> = [
    {
      file: 'topology.png',
      path: `/clusters/${clusterId}/topology`,
      // A node box, not the frame: the frame renders before the data arrives.
      ready: () => page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 }),
    },
    {
      file: 'queues.png',
      path: `/clusters/${clusterId}/queues`,
      ready: () => page.getByRole('row').nth(1).waitFor({ timeout: 30_000 }),
    },
    {
      file: 'metrics.png',
      path: `/clusters/${clusterId}/metrics?range=15m`,
      // Taller than the others on purpose: the dashboard is three stacked panels,
      // and a 900px crop cuts the second one in half.
      height: 1400,
      // recharts draws one tick after its container measures, so wait for a
      // plotted path rather than for the panel around it.
      ready: () =>
        page.locator('.recharts-area, .recharts-line').first().waitFor({ timeout: 30_000 }),
    },
    {
      file: 'sql.png',
      path: `/clusters/${clusterId}/sql`,
      height: 1100,
      // The console is an empty form until a query has been run, so this one is
      // driven rather than merely visited: an empty editor is a screenshot of
      // nothing.
      before: async () => {
        await page.getByRole('textbox', { name: /query/i }).click();
        // The console restores the last query, so a click leaves the cursor in
        // the middle of it and typing would splice the two together.
        await page.keyboard.press('ControlOrMeta+a');
        await page.keyboard.type(SQL);
        await page.getByRole('button', { name: 'Run', exact: true }).click();
      },
      ready: () => page.getByRole('row').nth(1).waitFor({ timeout: 30_000 }),
    },
    {
      file: 'governance.png',
      path: '/admin',
      ready: () => page.getByRole('row').nth(1).waitFor({ timeout: 30_000 }),
    },
  ];

  for (const shot of shots) {
    await page.setViewportSize({ width: 1440, height: shot.height ?? 900 });
    await page.goto(`${BASE}${shot.path}`);
    await shot.before?.();
    await shot.ready();
    await streamLive(page, shot.file);
    // One more frame, so the entry transition is finished rather than halfway.
    await page.waitForTimeout(750);
    await page.screenshot({ path: resolve(OUT, shot.file) });
    console.log(`wrote docs/img/${shot.file}`);
  }

  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

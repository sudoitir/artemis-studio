/**
 * Capture the README screenshots against a running Studio.
 *
 *   ADMIN_PASSWORD=… npm --prefix web run shots      (or: just shots)
 *
 * Point it at whatever `just demo` left running. It signs in as a real operator
 * would, waits for each view's own evidence of having loaded — never a fixed
 * sleep, which produces a screenshot of a skeleton often enough to matter — and
 * writes over `docs/img/*.png`.
 *
 * The viewport is fixed so the four images crop identically in the README table,
 * and `deviceScaleFactor: 2` so they stay legible when GitHub scales them down.
 */
import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../../docs/img');

const BASE = process.env.STUDIO ?? 'http://localhost:8080';
const USER = process.env.ADMIN_USER ?? 'admin';
const PASSWORD = process.env.ADMIN_PASSWORD;

if (!PASSWORD) {
  console.error('set ADMIN_PASSWORD to the password `just dev-up` printed');
  process.exit(1);
}
const password: string = PASSWORD;

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: 'dark',
  });

  await page.goto(`${BASE}/login`);
  await page.getByRole('textbox', { name: 'Username' }).fill(USER);
  // By role, not by label: the password field's visibility toggle carries the
  // same accessible name.
  await page.getByRole('textbox', { name: 'Password' }).fill(password);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30_000 });

  const clusterLink = page.getByRole('link', { name: /demo/i }).first();
  await clusterLink.click();
  await page.waitForURL(/\/clusters\/[0-9a-f-]+/, { timeout: 30_000 });
  const clusterId = new URL(page.url()).pathname.split('/')[2];

  const shots: Array<{
    file: string;
    path: string;
    height?: number;
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
      file: 'governance.png',
      path: '/admin',
      ready: () => page.getByRole('row').nth(1).waitFor({ timeout: 30_000 }),
    },
  ];

  for (const shot of shots) {
    await page.setViewportSize({ width: 1440, height: shot.height ?? 900 });
    await page.goto(`${BASE}${shot.path}`);
    await shot.ready();
    // The stream opens after the first paint, so without this every capture
    // shows the header mid-reconnect — a product that looks broken in its own
    // screenshots.
    await page
      .getByText('Live', { exact: true })
      .waitFor({ timeout: 20_000 })
      .catch(() => console.warn(`${shot.file}: stream not live at capture time`));
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

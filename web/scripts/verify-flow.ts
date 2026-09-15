/**
 * Real-browser verification of the Flow screen against a running, seeded Studio (not a capture).
 *
 *   ADMIN_PASSWORD=… node --experimental-strip-types scripts/verify-flow.ts
 *
 * Checks what jsdom cannot: the ELK worker lays the graph out, dots animate only when motion is
 * allowed, the keyboard reaches a node and the inspector returns focus, and how long reads and
 * frames take on the largest graph the server will draw. Prints a report; exits non-zero on failure.
 */
import { chromium, type Page } from '@playwright/test';
import { BASE, password, signIn } from './session.ts';

password();

/**
 * The browser globals the in-page checks touch. This script is type-checked against Node's
 * libraries, which have no DOM, so the callbacks reach them through this narrow view.
 */
type InPage = {
  document: {
    activeElement: { getAttribute(name: string): string | null } | null;
    querySelectorAll(selector: string): { length: number };
  };
  requestAnimationFrame(callback: () => void): number;
};

const results: Array<{ check: string; ok: boolean; detail: string }> = [];
const record = (check: string, ok: boolean, detail: string) => results.push({ check, ok, detail });

async function openFlow(page: Page, clusterId: string, query = '') {
  await page.goto(`${BASE}/clusters/${clusterId}/flow${query}`);
  await page.locator('.react-flow__node-queue').first().waitFor({ timeout: 90_000 });
}

async function main() {
  const browser = await chromium.launch();

  // 1. Reduced motion: laid out, no dots.
  {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    const clusterId = await signIn(page);
    await openFlow(page, clusterId);
    const nodes = await page.locator('.react-flow__node').count();
    const dots = await page.locator('animateMotion').count();
    const legend = await page.getByText(/Motion is off \(reduced motion\)/).count();
    record('reduced motion draws no dots', nodes > 0 && dots === 0 && legend === 1, `${nodes} nodes, ${dots} dots`);
    await context.close();
  }

  // 2. Motion on: dots animate; keyboard round trip; read latency; frame rate at the server's bound.
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const clusterId = await signIn(page);
  await openFlow(page, clusterId);
  await page.waitForTimeout(35_000); // two sampling sweeps, so rates exist
  await page.reload();
  await page.locator('.react-flow__node-queue').first().waitFor({ timeout: 90_000 });
  const dots = await page.locator('animateMotion').count();
  const rates = await page.getByText(/\d msg\/s/).count();
  record('dots animate on measured edges', dots > 0, `${dots} dots, ${rates} rate labels`);

  const firstNode = page.locator('.react-flow__node [role="button"]').first();
  await firstNode.focus();
  const focusedLabel = await firstNode.getAttribute('aria-label');
  await page.keyboard.press('Enter');
  const inspector = page.getByRole('complementary', { name: /^Details of / });
  const opened = await inspector.isVisible();
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);
  const closed = !(await inspector.isVisible());
  const focusBack = await page.evaluate(
    () => (globalThis as unknown as InPage).document.activeElement?.getAttribute('aria-label') ?? null,
  );
  record(
    'keyboard: Enter opens details, Escape closes and returns focus',
    opened && closed && focusBack === focusedLabel,
    `node "${focusedLabel}", focus after close "${focusBack}"`,
  );

  const timings: number[] = [];
  for (let i = 0; i < 10; i++) {
    const started = Date.now();
    const response = await page.request.get(`${BASE}/api/v1/clusters/${clusterId}/flow?limit=200&rank=IN&groupBy=CLIENT_ID`);
    await response.body();
    timings.push(Date.now() - started);
  }
  timings.sort((a, b) => a - b);
  record('flow read latency (limit 200)', timings[8] < 300, `p50 ${timings[4]} ms, p90 ${timings[8]} ms`);

  await openFlow(page, clusterId, '?limit=200');
  await page.waitForTimeout(3_000);
  const counts = await page.evaluate(() => {
    const { document } = globalThis as unknown as InPage;
    return {
      nodes: document.querySelectorAll('.react-flow__node').length,
      edges: document.querySelectorAll('.react-flow__edge').length,
    };
  });
  const fps = await page.evaluate(
    () =>
      new Promise<number>((resolve) => {
        let frames = 0;
        const start = performance.now();
        // Called on the global, not a detached reference: a native browser function detached from
        // window throws "Illegal invocation".
        const page = globalThis as unknown as InPage;
        const tick = () => {
          frames++;
          if (performance.now() - start < 5_000) page.requestAnimationFrame(tick);
          else resolve((frames * 1000) / (performance.now() - start));
        };
        page.requestAnimationFrame(tick);
      }),
  );
  record('frame rate at the largest bound', fps >= 50, `${fps.toFixed(1)} fps over ${counts.nodes} nodes, ${counts.edges} edges`);

  await context.close();
  await browser.close();

  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.check} — ${r.detail}`);
  if (results.some((r) => !r.ok)) process.exit(1);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

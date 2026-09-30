/**
 * Real-browser verification of grid column sizing (ADR-0116) against a running, seeded Studio.
 *
 *   ADMIN_PASSWORD=… ADMIN_TOTP_SECRET=… node --experimental-strip-types scripts/verify-grid.ts
 *
 * Checks what jsdom cannot: columns fit what the browser actually lays out, a drag on a header
 * border widens its column, header and rows stay aligned, the width survives a reload, a
 * double-click fits, and Ctrl+Shift+Arrow resizes from the keyboard. It also captures the queues
 * grid and the SQL console in both colour schemes to SHOTS (default `grid-shots/`). Prints a
 * report; exits non-zero on failure.
 */
import { mkdir } from 'node:fs/promises';
import { chromium, type Page } from '@playwright/test';
import { BASE, password, signIn, streamLive } from './session.ts';

password();
const OUT = process.env.SHOTS ?? 'grid-shots';

const results: Array<{ check: string; ok: boolean; detail: string }> = [];
const record = (check: string, ok: boolean, detail: string) => results.push({ check, ok, detail });

const header = (page: Page, name: string) => page.getByRole('columnheader', { name, exact: false }).first();

async function widthOf(page: Page, name: string): Promise<number> {
  return (await header(page, name).boundingBox())?.width ?? 0;
}

/** Every header cell's inline start against the first body row's, in px. */
async function misalignment(page: Page): Promise<number> {
  return page.evaluate(() => {
    const doc = (
      globalThis as unknown as {
        document: {
          querySelector(s: string): { children: ArrayLike<{ getBoundingClientRect(): { left: number } }> } | null;
        };
      }
    ).document;
    const head = doc.querySelector('[role="grid"] [data-grid-row="0"]');
    const body = doc.querySelector('[role="grid"] [data-grid-row="1"]');
    if (!head || !body) return Number.POSITIVE_INFINITY;
    let worst = 0;
    for (let i = 0; i < head.children.length; i++) {
      worst = Math.max(
        worst,
        Math.abs(head.children[i].getBoundingClientRect().left - body.children[i].getBoundingClientRect().left),
      );
    }
    return worst;
  });
}

/** Queue-column cells whose value is cut, among those narrower than `under` px. */
async function cutQueueCells(page: Page, under: number): Promise<number> {
  const col = await header(page, 'Queue').getAttribute('data-grid-col');
  return page.evaluate(
    ([limit, index]) => {
      const doc = (
        globalThis as unknown as {
          document: { querySelectorAll(s: string): ArrayLike<{ scrollWidth: number; clientWidth: number }> };
        }
      ).document;
      const cells = doc.querySelectorAll(
        `[role="grid"] [data-grid-row]:not([data-grid-row="0"]) > [data-grid-col="${index}"]`,
      );
      let n = 0;
      for (let i = 0; i < cells.length; i++)
        if (cells[i].scrollWidth > cells[i].clientWidth && cells[i].scrollWidth < limit) n++;
      return n;
    },
    [under, col] as const,
  );
}

async function openQueues(page: Page, clusterId: string) {
  await page.goto(`${BASE}/clusters/${clusterId}/queues`);
  await page.getByRole('grid', { name: 'Queues' }).locator('[data-grid-row="1"]').waitFor({ timeout: 60_000 });
}

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const clusterId = await signIn(page);
  await page.evaluate(() => (globalThis as unknown as { localStorage: { clear(): void } }).localStorage.clear());
  await openQueues(page, clusterId);

  // 1. Fit: no Queue cell is cut while its value is under the cap.
  const cut = await cutQueueCells(page, 480);
  record('queue names under the cap fit without an ellipsis', cut === 0, `${cut} cut`);

  // 2. Drag the Queue column's border 80 px wider.
  const before = await widthOf(page, 'Queue');
  const box = (await header(page, 'Queue').boundingBox())!;
  await page.mouse.move(box.x + box.width - 3, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width + 40, box.y + box.height / 2, { steps: 5 });
  await page.mouse.move(box.x + box.width + 77, box.y + box.height / 2, { steps: 5 });
  await page.mouse.up();
  const dragged = await widthOf(page, 'Queue');
  record('dragging a header border widens its column', Math.abs(dragged - before - 80) <= 3, `${before} → ${dragged}`);
  record(
    'header and rows stay aligned after a drag',
    (await misalignment(page)) <= 1,
    `${await misalignment(page)} px`,
  );

  // 3. The width survives a reload.
  await openQueues(page, clusterId);
  const reloaded = await widthOf(page, 'Queue');
  record('a width survives a reload', Math.abs(reloaded - dragged) <= 1, `${dragged} → ${reloaded}`);

  // 4. Keyboard: Ctrl+Shift+Right on the focused header, announced.
  await header(page, 'Queue').getByRole('button').focus();
  await page.keyboard.press('Control+Shift+ArrowRight');
  const keyed = await widthOf(page, 'Queue');
  const said = await page
    .getByRole('status')
    .filter({ hasText: /Queue column, \d+ pixels/ })
    .count();
  record(
    'Ctrl+Shift+Right widens by 16 px and says so',
    Math.abs(keyed - reloaded - 16) <= 1 && said > 0,
    `${reloaded} → ${keyed}, announced ${said > 0}`,
  );

  // 5. Double-click fits the column back to its content.
  const handle = header(page, 'Queue').locator('span[aria-hidden="true"]').last();
  await handle.dblclick();
  const fitted = await widthOf(page, 'Queue');
  const cutAfterFit = await cutQueueCells(page, Number.POSITIVE_INFINITY);
  record(
    'double-clicking the border fits the column to every value',
    cutAfterFit === 0 && fitted !== keyed,
    `${keyed} → ${fitted}, ${cutAfterFit} cut`,
  );

  // 6. Screenshots, light and dark.
  for (const scheme of ['light', 'dark'] as const) {
    const shot = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });
    // Studio keeps its own scheme (Mantine's stored choice), not the browser's preference.
    await shot.addInitScript((value) => {
      (globalThis as unknown as { localStorage: { setItem(k: string, v: string): void } }).localStorage.setItem(
        'mantine-color-scheme-value',
        value,
      );
    }, scheme);
    const p = await shot.newPage();
    const id = await signIn(p);
    await openQueues(p, id);
    await streamLive(p, 'queues');
    await header(p, 'Address').hover();
    await p.screenshot({ path: `${OUT}/queues-${scheme}.png` });
    await p.goto(`${BASE}/clusters/${id}/sql`);
    await p.getByRole('textbox', { name: /query/i }).click();
    await p.keyboard.press('ControlOrMeta+a');
    await p.keyboard.type(
      'SELECT name, messageCount FROM queues -- the deepest first\nWHERE messageCount > 0\nORDER BY messageCount DESC',
    );
    await p.screenshot({ path: `${OUT}/sql-${scheme}.png` });
    await shot.close();
  }

  await browser.close();
  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.check}  (${r.detail})`);
  if (results.some((r) => !r.ok)) process.exit(1);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

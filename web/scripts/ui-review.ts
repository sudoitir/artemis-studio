/**
 * Capture the routing builder for a design pass, in BOTH colour schemes.
 *
 *   ADMIN_PASSWORD=… npm --prefix web run ui-review
 *
 * Not a README capture — `shots.ts` is that. This one photographs the states a
 * reviewer needs to judge rather than the one that sells the feature: the canvas
 * idle, an element selected, each editor open, and a canvas node holding keyboard
 * focus, which is the state most likely to have been implemented and never looked at.
 *
 * Both schemes, because the light one has to be measured independently — Mantine's
 * yellow and orange ramps never reach 4.5:1 on white at any step, so a warning that
 * reads fine in the dark scheme is exactly where the light one breaks.
 */
import { chromium, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, password, signIn } from './session.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../../.ui-review');

password();

interface Shot {
  file: string;
  /** Drives the canvas into the state worth judging. */
  before?: (page: Page) => Promise<unknown>;
  ready: (page: Page) => Promise<unknown>;
  width?: number;
  height?: number;
}

const SHOTS: Shot[] = [
  {
    file: 'canvas',
    ready: (page) => page.locator('.react-flow__node').first().waitFor({ timeout: 60_000 }),
  },
  {
    file: 'canvas-wide',
    width: 1920,
    height: 1200,
    ready: (page) => page.locator('.react-flow__node').first().waitFor({ timeout: 60_000 }),
  },
  {
    file: 'inspector',
    before: async (page) => {
      await page.locator('.react-flow__node').first().waitFor({ timeout: 60_000 });
      await page.locator('.react-flow__node').first().click();
    },
    ready: (page) => page.getByRole('complementary').or(page.getByRole('region')).first().waitFor({ timeout: 15_000 }),
  },
  {
    file: 'keyboard-focus',
    // The state nobody looks at: a canvas node holding real keyboard focus.
    before: async (page) => {
      await page.locator('.react-flow__node').first().waitFor({ timeout: 60_000 });
      // `:focus-within` rather than reading document.activeElement, so this stays a
      // locator query and the script needs no DOM lib to typecheck.
      for (let i = 0; i < 40; i++) {
        await page.keyboard.press('Tab');
        if ((await page.locator('.react-flow__node:focus-within').count()) > 0) return;
      }
      console.warn('keyboard-focus: never reached a canvas node by tabbing — that is itself a finding');
    },
    ready: () => Promise.resolve(),
  },
  {
    file: 'bridge-editor',
    before: async (page) => {
      const add = page.getByRole('button', { name: /bridge/i }).first();
      await add.waitFor({ timeout: 20_000 }).catch(() => console.warn('bridge-editor: no bridge control found'));
      await add.click().catch(() => undefined);
    },
    ready: (page) => page.getByRole('dialog').first().waitFor({ timeout: 15_000 }),
    height: 1100,
  },
  {
    file: 'divert-editor',
    before: async (page) => {
      const add = page.getByRole('button', { name: /divert/i }).first();
      await add.waitFor({ timeout: 20_000 }).catch(() => console.warn('divert-editor: no divert control found'));
      await add.click().catch(() => undefined);
      // The transformer is behind the same disclosure the divert editor already had.
      await page
        .getByRole('button', { name: /advanced/i })
        .first()
        .click()
        .catch(() => undefined);
    },
    ready: (page) => page.getByRole('dialog').first().waitFor({ timeout: 15_000 }),
    height: 1100,
  },
];

/**
 * The routing tab only exists once something is declared — before that the screen is
 * the first-run adoption offer, which is correct and is also why a capture run against
 * a fresh cluster photographs nothing. Adopt once, then photograph.
 */
async function ensureDeclaration(page: Page, clusterId: string) {
  await page.goto(`${BASE}/clusters/${clusterId}/configuration`);
  // The tabs are always rendered — the first-run offer sits above them — so the tab's
  // presence says nothing about whether anything is declared. The offer itself does.
  const adopt = page.getByRole('button', { name: /Review and adopt as revision 1/i });
  await adopt.waitFor({ timeout: 60_000 }).catch(() => undefined);
  if (!(await adopt.count())) {
    console.log('already declared');
    return;
  }
  await adopt.click();
  const save = page.getByRole('button', { name: /^Save as revision/i });
  await save.waitFor({ timeout: 30_000 });
  await save.click();
  // Adopted when the offer is gone, not when a timer says so.
  await adopt.waitFor({ state: 'detached', timeout: 60_000 });
  console.log('adopted a declaration so the routing tab has something to draw');
}

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await chromium.launch();

  for (const scheme of ['dark', 'light'] as const) {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 900 },
      deviceScaleFactor: 2,
      colorScheme: scheme,
    });
    // Mantine keys off its own storage, not prefers-color-scheme, so setting the
    // Playwright colorScheme alone leaves the app in its default and photographs the
    // dark scheme twice under two filenames — a harness that quietly claims to have
    // checked the light one.
    await page.addInitScript((s) => localStorage.setItem('mantine-color-scheme-value', s), scheme);
    const clusterId = await signIn(page);
    await ensureDeclaration(page, clusterId);
    const path = `/clusters/${clusterId}/configuration?tab=routing`;

    for (const shot of SHOTS) {
      await page.setViewportSize({ width: shot.width ?? 1440, height: shot.height ?? 900 });
      await page.goto(`${BASE}${path}`);
      try {
        await shot.before?.(page);
        await shot.ready(page);
      } catch (error) {
        console.warn(`skipped ${shot.file} (${scheme}): ${(error as Error).message.split('\n')[0]}`);
        continue;
      }
      await page.waitForTimeout(750);
      await page.screenshot({ path: resolve(OUT, `${shot.file}-${scheme}.png`) });
      console.log(`wrote .ui-review/${shot.file}-${scheme}.png`);
    }
    await page.close();
  }

  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

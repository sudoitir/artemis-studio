/**
 * Record the README's demo GIFs from a real signed-in session.
 *
 *   ADMIN_PASSWORD=… npm --prefix web run demo      (or: just demo-gif)
 *
 * Point it at whatever `just demo` left running. Everything on screen is the
 * product answering for itself against the seeded four-node estate — there is no
 * scripted output and nothing is drawn for the camera.
 *
 * Two clips, because they answer two questions and a viewer only watches the
 * first one: `demo.gif` is "what is this", `sql-console.gif` is "what is the
 * thing you cannot do anywhere else".
 *
 * Playwright records WebM per context; ffmpeg turns each into a GIF through a
 * generated palette (a 256-colour default palette turns a dark UI into mud) and
 * an H.264 MP4 beside it.
 */
import { chromium, type Page } from '@playwright/test';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { BASE, password, signIn, streamLive } from './session.ts';

const run = promisify(execFile);
const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../../docs/img');

// 1280×800 rather than the screenshots' 1440×900: a GIF pays for every pixel in
// bytes, and GitHub renders the README column narrower than either. The GIF is
// then scaled again on encode — 1000px wide at 10fps keeps both clips small
// enough that a README on a phone connection still loads.
const SIZE = { width: 1280, height: 800 };
const GIF_WIDTH = 1000;
const FPS = 10;

password();

/** Types into an editor at a speed a viewer can read rather than instantly. */
async function type(page: Page, text: string) {
  await page.keyboard.type(text, { delay: 45 });
}

/** Holds the last frame, so a GIF loop does not snap away from the payoff. */
async function hold(page: Page, ms: number) {
  await page.waitForTimeout(ms);
}

async function encode(webm: string, name: string, skip: number) {
  // Recording starts when the context does, so the first seconds are the login
  // screen and the navigation to the first view. `mark()` says where the part
  // worth watching begins; everything before it is cut.
  const trim = ['-ss', skip.toFixed(2), '-i', webm];
  // 128 colours rather than 256: a dark UI with a small accent palette does not
  // use them, and the smaller table is a visibly smaller file.
  const palette = join(dirname(webm), 'palette.png');
  const filters = `fps=${FPS},scale=${GIF_WIDTH}:-1:flags=lanczos`;
  await run('ffmpeg', ['-y', ...trim, '-vf', `${filters},palettegen=max_colors=128:stats_mode=diff`, palette]);
  await run('ffmpeg', [
    '-y',
    ...trim,
    '-i', palette,
    // No dithering: this is a flat, dark UI with large areas of one colour, so
    // dithering buys nothing visible and roughly doubles the file.
    '-lavfi', `${filters}[x];[x][1:v]paletteuse=dither=none:diff_mode=rectangle`,
    join(OUT, `${name}.gif`),
  ]);
  // The MP4 is a fraction of the size and is what a page with a player should
  // prefer; the GIF is what GitHub can render inline.
  await run('ffmpeg', [
    '-y',
    ...trim,
    '-vf', `scale=${SIZE.width}:-2`,
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '26', '-movflags', '+faststart', '-an',
    join(OUT, `${name}.mp4`),
  ]);
  console.log(`wrote docs/img/${name}.gif and ${name}.mp4`);
}

/** One clip: its own context, so its own video file. */
async function clip(
  name: string,
  drive: (page: Page, clusterId: string, mark: () => void) => Promise<void>,
) {
  const dir = await mkdtemp(join(tmpdir(), `artemis-studio-${name}-`));
  const browser = await chromium.launch();
  const started = Date.now();
  const context = await browser.newContext({
    viewport: SIZE,
    colorScheme: 'dark',
    recordVideo: { dir, size: SIZE },
  });
  const page = await context.newPage();
  let skip = 0;
  try {
    const clusterId = await signIn(page);
    await drive(page, clusterId, () => {
      skip = (Date.now() - started) / 1000;
    });
  } finally {
    await context.close(); // the video is only flushed here
    await browser.close();
  }
  const [video] = (await readdir(dir)).filter((f) => f.endsWith('.webm'));
  if (!video) throw new Error(`${name}: playwright wrote no video into ${dir}`);
  await encode(join(dir, video), name, skip);
  await rm(dir, { recursive: true, force: true });
}

await mkdir(OUT, { recursive: true });

// ── 1. What Artemis Studio is ────────────────────────────────────────────────
await clip('demo', async (page, clusterId, mark) => {
  await page.goto(`${BASE}/clusters/${clusterId}/topology`);
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 });
  await streamLive(page, 'demo');
  // Only now: everything before this is a login form and a reconnecting header.
  mark();
  await hold(page, 4_000);

  // Every queue on every node, worst first — the view the bundled console cannot
  // produce at all.
  await page.goto(`${BASE}/clusters/${clusterId}/queues`);
  await page.getByRole('row').nth(1).waitFor({ timeout: 30_000 });
  await streamLive(page, 'demo');
  await hold(page, 1_500);
  // Sorting by depth is the whole point of the view: the worst thing in the
  // cluster becomes the first row. The header is a button inside the columnheader.
  const depth = page.getByRole('button', { name: /^depth/i }).first();
  await depth.click().catch(() => console.warn('demo: no depth column to sort by'));
  await hold(page, 1_200);
  await depth.click().catch(() => {}); // ascending, then descending
  await hold(page, 3_000);

  // The dead-letter queues the seed really built, by rejecting messages.
  await page.goto(`${BASE}/clusters/${clusterId}/dlq`);
  // The DLQ view is cards, not a grid — waiting for a row here waits for a
  // timeout and puts twelve dead seconds in the middle of the clip.
  await page.getByText(/dead-letter queues/i).first().waitFor({ timeout: 20_000 });
  await hold(page, 3_500);

  await page.goto(`${BASE}/clusters/${clusterId}/metrics?range=15m`);
  await page
    .locator('.recharts-area, .recharts-line')
    .first()
    .waitFor({ timeout: 30_000 })
    .catch(() => console.warn('demo: no plotted series yet — let the seed run longer'));
  await hold(page, 4_500);
});

// ── 2. The SQL Console, on its own ───────────────────────────────────────────
await clip('sql-console', async (page, clusterId, mark) => {
  await page.goto(`${BASE}/clusters/${clusterId}/sql`);
  const editor = page.getByRole('textbox', { name: /query/i });
  await editor.waitFor({ timeout: 30_000 });
  await streamLive(page, 'sql-console');
  mark();
  await hold(page, 1_000);

  // Cheap first: the predicate pushes down into a JMS selector, and the plan
  // strip says so before anything is run.
  await editor.click();
  // The console restores the last query; a click alone leaves the cursor in the
  // middle of it.
  await page.keyboard.press('ControlOrMeta+a');
  await type(page, 'SELECT * FROM "ORDERS.*"\nWHERE props.tenant = \'acme\'\nLIMIT 200');
  await hold(page, 2_500); // let the plan strip classify it
  await page.getByRole('button', { name: 'Run', exact: true }).click();
  await page.getByRole('row').nth(1).waitFor({ timeout: 60_000 }).catch(() => {});
  await page.mouse.wheel(0, 260);
  await hold(page, 3_500);

  // Then the thing a JMS selector cannot do at all: read the body.
  await page.mouse.wheel(0, -260);
  await editor.click();
  await page.keyboard.press('ControlOrMeta+a');
  await type(page, 'SELECT * FROM "ORDERS.*"\nWHERE body->>\'orderId\' = \'4471\'\nLIMIT 50');
  await hold(page, 3_000); // the plan strip now says "scan", which is the point
  await page.getByRole('button', { name: 'Run', exact: true }).click();
  await page.getByRole('row').nth(1).waitFor({ timeout: 60_000 }).catch(() => {});
  await page.mouse.wheel(0, 260);
  await hold(page, 4_500);
});

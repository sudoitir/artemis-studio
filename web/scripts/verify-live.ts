/**
 * The live pause/refresh/window checks, driven rather than clicked.
 *
 *   ADMIN_PASSWORD=… node --experimental-strip-types web/scripts/verify-live.ts
 *
 * Point it at a running `just demo`. What it prints is the evidence for the
 * behaviours the unit tests can only assert in jsdom: that pausing really does
 * silence the network, that resuming issues exactly one burst, and that a
 * relative metrics window advances by one bucket and not by one second.
 *
 * Expected: a small number of trailing polls right after pausing (one per polling
 * query — the ceiling recorded in ADR-0055), then zero across navigation.
 *
 * Every step after sign-in navigates through the router, never `page.goto`: a
 * full reload drops the query cache and resets the pause flag, which is memory-only
 * on purpose (ADR-0052), so a reload-based script measures the wrong thing.
 */
import { chromium } from '@playwright/test';

const BASE = 'http://localhost:8080';
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 1440, height: 900 } });

const api: string[] = [];
p.on('request', (r) => {
  const u = new URL(r.url());
  if (u.pathname.startsWith('/api/v1/') && !u.pathname.startsWith('/api/v1/stream')) api.push(u.pathname);
});

await p.goto(`${BASE}/login`);
await p.getByRole('textbox', { name: 'Username' }).fill('admin');
await p.getByRole('textbox', { name: 'Password' }).fill(process.env.ADMIN_PASSWORD!);
await p.getByRole('button', { name: /sign in|log in/i }).click();
await p.waitForURL((u) => !u.pathname.startsWith('/login'));
await p.getByRole('link', { name: /demo/i }).first().click();
await p.waitForURL(/\/clusters\/[0-9a-f-]+/);

const go = async (view: RegExp) => {
  await p.getByRole('link', { name: view }).first().click();
  await p.waitForTimeout(2500);
};

// Warm every view the pause test will revisit, so none of them is exempt as a
// query that has never resolved.
for (const v of [/queues/i, /topology/i, /events/i, /metrics/i]) await go(v);
await p.locator('.recharts-area, .recharts-line').first().waitFor();

await p.getByRole('button', { name: 'Pause auto-refresh' }).click();

api.length = 0;
await p.waitForTimeout(20_000);
console.log(`paused, sitting still 20s -> ${api.length} requests`, [...new Set(api)]);

api.length = 0;
for (const v of [/queues/i, /topology/i, /events/i]) await go(v);
console.log(`paused, three navigations -> ${api.length} requests`, [...new Set(api)]);

api.length = 0;
await p.getByRole('button', { name: 'Resume auto-refresh' }).click();
await p.waitForTimeout(1500);
console.log(`resume burst -> ${api.length} requests`);

// A relative window advances by exactly one bucket; 15m buckets are 15s.
await go(/metrics/i);
await p.locator('.recharts-area, .recharts-line').first().waitFor();
const windows: string[] = [];
p.on('request', (r) => {
  const u = new URL(r.url());
  if (u.pathname.endsWith('/metrics')) windows.push(`${u.searchParams.get('from')}..${u.searchParams.get('to')}`);
});
await p.waitForTimeout(40_000);
const distinct = [...new Set(windows)];
console.log(`metrics windows over 40s: ${distinct.length} distinct`);
distinct.forEach((w) => console.log('  ', w));

await b.close();

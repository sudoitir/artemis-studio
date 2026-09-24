/**
 * A plugin's whole life against a running Studio, through its public API and its UI (task 10.7):
 * install the template plugin, use its API and its UI bundle, update it with no restart, roll it
 * back, then disable, uninstall and purge it. Studio's own CI runs this against a fresh build.
 *
 *   STUDIO=http://localhost:8080 ADMIN_PASSWORD=… NEW_PASSWORD=… \
 *   JAR_V1=…/acme-notes-1.0.0.jar JAR_V2=…/acme-notes-1.0.1.jar \
 *   node --experimental-strip-types scripts/plugin-e2e.ts
 *
 * ADMIN_PASSWORD is the one Studio printed at first start; when the account must still change it,
 * NEW_PASSWORD becomes the password.
 */
import { chromium, request, type APIRequestContext } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const BASE = process.env.STUDIO ?? 'http://localhost:8080';
const USER = process.env.ADMIN_USER ?? 'admin';
const ID = 'acme-notes';

function need(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`set ${name}`);
  return value;
}

function step(message: string) {
  console.log(`→ ${message}`);
}

async function csrf(api: APIRequestContext): Promise<string> {
  const cookies = (await api.storageState()).cookies;
  return cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
}

async function call(api: APIRequestContext, method: string, path: string, body?: unknown, raw?: Buffer) {
  const headers: Record<string, string> = { 'X-XSRF-TOKEN': await csrf(api) };
  const response = await api.fetch(`/api/v1${path}`, {
    method,
    headers: raw ? { ...headers, 'content-type': 'application/octet-stream' } : headers,
    data: raw ?? (body === undefined ? undefined : body),
  });
  const text = await response.text();
  return { status: response.status(), body: text ? JSON.parse(text) : undefined };
}

async function expectStatus(result: { status: number; body: unknown }, want: number, what: string) {
  if (result.status !== want) throw new Error(`${what}: expected ${want}, got ${result.status} ${JSON.stringify(result.body)}`);
  return result.body as never;
}

async function awaitPlugin(api: APIRequestContext, want: { status: string; version?: string }) {
  const deadline = Date.now() + 90_000;
  let last: { status?: string; version?: string; failure?: string } = {};
  while (Date.now() < deadline) {
    last = (await call(api, 'GET', `/admin/plugins/${ID}`)).body ?? {};
    if (last.status === 'failed') throw new Error(`${ID} failed: ${last.failure}`);
    if (last.status === want.status && (!want.version || last.version === want.version)) return;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`${ID} never reached ${JSON.stringify(want)}; last ${JSON.stringify(last)}`);
}

/** Plugin changes need a sign-in within five minutes (ADR-0103); a slow run confirms again. */
async function fresh(api: APIRequestContext, password: string) {
  await expectStatus(await call(api, 'POST', '/auth/reauthenticate', { password }), 200, 'reauthenticate');
}

async function install(api: APIRequestContext, jar: string, expectedClass: string) {
  const upload = await expectStatus(await call(api, 'PUT', '/admin/plugins/upload', undefined, await readFile(jar)), 201, `upload ${jar}`) as {
    sha256: string;
    plan: { activationClass: string; toVersion: string };
  };
  if (upload.plan.activationClass !== expectedClass) {
    throw new Error(`expected ${expectedClass}, planned ${upload.plan.activationClass}`);
  }
  await expectStatus(await call(api, 'POST', `/admin/plugins/uploads/${upload.sha256}/activate`), 202, 'activate');
  await awaitPlugin(api, { status: 'active', version: upload.plan.toVersion });
  return upload.plan.toVersion;
}

async function main() {
  const api = await request.newContext({ baseURL: BASE });
  let password = need('ADMIN_PASSWORD');

  step('sign in');
  await api.get('/api/v1/auth/providers');
  const me = await expectStatus(await call(api, 'POST', '/auth/login', { username: USER, password }), 200, 'login') as {
    mustChangePassword: boolean;
  };
  if (me.mustChangePassword) {
    const next = need('NEW_PASSWORD');
    await expectStatus(await call(api, 'POST', '/auth/password', { currentPassword: password, newPassword: next }), 204, 'change password');
    password = next;
    await expectStatus(await call(api, 'POST', '/auth/login', { username: USER, password }), 200, 'login again');
  }

  step('install 1.0.0 — a new schema, so Brief maintenance');
  await install(api, need('JAR_V1'), 'BRIEF_MAINTENANCE');

  step("use the plugin's own API");
  const cluster = randomUUID();
  await expectStatus(await call(api, 'POST', `/clusters/${cluster}/p/${ID}/queues/orders/notes`, { body: 'owned by payments' }), 201, 'add a note');
  const notes = await expectStatus(await call(api, 'GET', `/clusters/${cluster}/p/${ID}/queues/orders/notes`), 200, 'list notes') as unknown[];
  if (notes.length !== 1) throw new Error(`expected 1 note, got ${notes.length}`);

  step('its UI bundle is served where the manifest says');
  const manifest = (await call(api, 'GET', '/manifest')).body as { features: { id: string; ui?: { entry: string } }[] };
  const entry = manifest.features.find((f) => f.id === ID)?.ui?.entry;
  if (!entry) throw new Error('the manifest names no UI entry');
  const bundle = await api.get(entry);
  if (bundle.status() !== 200 || !bundle.headers()['content-type']?.startsWith('text/javascript')) {
    throw new Error(`${entry}: ${bundle.status()} ${bundle.headers()['content-type']}`);
  }

  step('the page starts with the plugin loaded');
  const browser = await chromium.launch();
  const page = await browser.newPage({ storageState: await api.storageState(), baseURL: BASE });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('/admin?tab=plugins');
  await page.getByRole('row', { name: /Notes/ }).getByText('Active').waitFor({ timeout: 30_000 });
  if (await page.getByText(/could not show (its|their) screens/).count()) throw new Error('the plugin UI did not load');
  if (errors.length) throw new Error(`page errors: ${errors.join('; ')}`);
  await browser.close();

  step('update to 1.0.1 — no database change, so no downtime');
  await fresh(api, password);
  await install(api, need('JAR_V2'), 'INSTANT');

  step('roll back to 1.0.0; the data is still there');
  await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/rollback`), 202, 'rollback');
  await awaitPlugin(api, { status: 'active', version: '1.0.0' });
  const kept = await expectStatus(await call(api, 'GET', `/clusters/${cluster}/p/${ID}/queues/orders/notes`), 200, 'notes after rollback') as unknown[];
  if (kept.length !== 1) throw new Error('rollback lost data');

  step('disable: its API is gone');
  await fresh(api, password);
  await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/disable`), 204, 'disable');
  const gone = await call(api, 'GET', `/clusters/${cluster}/p/${ID}/queues/orders/notes`);
  if (gone.status !== 404) throw new Error(`disabled plugin answered ${gone.status}`);

  step('uninstall, then purge after a dry run');
  await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/uninstall`), 204, 'uninstall');
  const estimate = await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/purge?dryRun=true`), 200, 'purge dry run') as {
    tables: { name: string }[];
  };
  if (!estimate.tables.some((t) => t.name === 'note')) throw new Error('the dry run does not name the note table');
  await expectStatus(await call(api, 'POST', `/admin/plugins/${ID}/purge?dryRun=false`), 200, 'purge');
  await expectStatus(await call(api, 'GET', `/admin/plugins/${ID}`), 404, 'purged plugin');

  console.log('✓ plugin lifecycle passed');
}

main().catch((e) => {
  console.error(`✗ ${e instanceof Error ? e.message : String(e)}`);
  process.exit(1);
});

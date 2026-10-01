/**
 * Sign in once per account and save the browser session, so the sweep never signs in again.
 *
 *   STUDIO=… ADMIN_PASSWORD=… ADMIN_TOTP_SECRET=… QA_USER_PASSWORD=… \
 *     node --experimental-strip-types web/scripts/sweep/auth.ts        (`just qa-up` runs it)
 *
 * One sign-in per account, not one per capture: a second factor Studio has seen is refused as a replay
 * (ADR-0143), and repeated sign-ins would come close to the lockout (ADR-0144). The state goes to
 * `web/.sweep/auth/<account>.json`: `admin` for everything, `reader` (the read-only `qa-reader` that
 * `scripts/qa-seed.sh` creates) for the permission-denied captures.
 */
import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { USER, login } from '../session.ts';

const OUT = resolve(dirname(fileURLToPath(import.meta.url)), '../../.sweep/auth');

const need = (name: string): string => {
  const value = process.env[name];
  if (!value) {
    console.error(`set ${name}`);
    process.exit(1);
  }
  return value;
};

const accounts = [
  { name: 'admin', user: USER, password: need('ADMIN_PASSWORD'), secret: need('ADMIN_TOTP_SECRET') },
  { name: 'reader', user: 'qa-reader', password: need('QA_USER_PASSWORD'), secret: undefined },
];

await mkdir(OUT, { recursive: true });
const browser = await chromium.launch();
for (const { name, user, password, secret } of accounts) {
  const context = await browser.newContext();
  await login(await context.newPage(), user, password, secret);
  await context.storageState({ path: resolve(OUT, `${name}.json`) });
  await context.close();
  console.log(`saved ${name} (${user}) to web/.sweep/auth/${name}.json`);
}
await browser.close();

/**
 * Sign in to a running Studio the way an operator does, and find the demo cluster.
 *
 * Shared by `shots.ts` and `demo.ts` so the two cannot drift: a change to the
 * login screen should break both captures at once, in one place.
 */
import type { Browser, BrowserContext, Page } from '@playwright/test';

import { nextTotp } from './totp.ts';

export const BASE = process.env.STUDIO ?? 'http://localhost:8080';
export const USER = process.env.ADMIN_USER ?? 'admin';

export function password(): string {
  const value = process.env.ADMIN_PASSWORD;
  if (!value) {
    console.error('set ADMIN_PASSWORD to the password `just dev-up` printed');
    process.exit(1);
  }
  return value;
}

/** The secret of the account's authenticator app: the built-in ADMIN role requires one (ADR-0143). */
function totpSecret(): string {
  const value = process.env.ADMIN_TOTP_SECRET;
  if (!value) {
    console.error('set ADMIN_TOTP_SECRET to the secret `just demo` printed when it set up two-step verification');
    process.exit(1);
  }
  return value;
}

const leftLogin = (page: Page, timeout: number) =>
  page
    .waitForURL((url) => !url.pathname.startsWith('/login'), { timeout })
    .then(
      () => true,
      () => false,
    );

/** The second step: the current code, and the next one if that was refused as already used by another run. */
async function secondStep(page: Page, secret: string) {
  for (let attempt = 0; attempt < 3; attempt++) {
    await page.getByRole('textbox', { name: 'Code from your authenticator app' }).fill(await nextTotp(secret));
    await page.getByRole('button', { name: 'Verify' }).click();
    if (await leftLogin(page, 10_000)) return;
  }
  throw new Error('Studio refused three second-step codes in a row');
}

/** Signs one account in through the login screen, through the second step (with `secret`, else ADMIN_TOTP_SECRET) when Studio asks for it. */
export async function login(page: Page, user: string, passphrase: string, secret?: string) {
  await page.goto(`${BASE}/login`);
  await page.getByRole('textbox', { name: 'Username' }).fill(user);
  // By role, not by label: the password field's visibility toggle carries the
  // same accessible name.
  await page.getByRole('textbox', { name: 'Password' }).fill(passphrase);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  const code = page.getByRole('textbox', { name: 'Code from your authenticator app' });
  const next = await Promise.race([
    code.waitFor({ timeout: 30_000 }).then(() => 'second-step' as const),
    leftLogin(page, 30_000).then(() => 'in' as const),
  ]).catch(() => 'stuck' as const);
  if (next === 'second-step') await secondStep(page, secret ?? totpSecret());
  else if (next === 'stuck') throw new Error(`still on the login page: ${page.url()}`);
}

/** Signs in, through the second step when the account has one, and returns the id of the cluster the demo seed registered. */
export async function signIn(page: Page): Promise<string> {
  await login(page, USER, password());
  await page.getByRole('link', { name: /demo/i }).first().click();
  await page.waitForURL(/\/clusters\/[0-9a-f-]+/, { timeout: 30_000 });
  return new URL(page.url()).pathname.split('/')[2]!;
}

/**
 * Wait for the stream to say it is live before capturing.
 *
 * Without this every capture shows the header mid-reconnect — a product that
 * looks broken in its own screenshots.
 */
export async function streamLive(page: Page, label: string) {
  await page
    .getByText('Live', { exact: true })
    .waitFor({ timeout: 20_000 })
    .catch(() => console.warn(`${label}: stream not live at capture time`));
}

export type Session = { browser: Browser; context: BrowserContext; page: Page };

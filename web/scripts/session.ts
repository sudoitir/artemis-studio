/**
 * Sign in to a running Studio the way an operator does, and find the demo cluster.
 *
 * Shared by `shots.ts` and `demo.ts` so the two cannot drift: a change to the
 * login screen should break both captures at once, in one place.
 */
import type { Browser, BrowserContext, Page } from '@playwright/test';

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

/** Signs in and returns the id of the cluster the demo seed registered. */
export async function signIn(page: Page): Promise<string> {
  await page.goto(`${BASE}/login`);
  await page.getByRole('textbox', { name: 'Username' }).fill(USER);
  // By role, not by label: the password field's visibility toggle carries the
  // same accessible name.
  await page.getByRole('textbox', { name: 'Password' }).fill(password());
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30_000 });

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

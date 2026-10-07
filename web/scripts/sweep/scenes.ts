/**
 * The states of a screen that need acting on it: filling a form, running a check, opening a dialog.
 * Each is a `Scene` in routes.ts, with the route it belongs to, and is captured with the same checks as a
 * route. They address the page the way a person does, by role and label.
 *
 * They are written for the isolated stack `just qa-up` makes: its brokers answer on `artemis-primary` and
 * `artemis-backup` inside the compose network, with the account `artemis`.
 */
import type { Page } from '@playwright/test';

import type { Scene } from './routes.ts';

/** The primary broker's management URL, as Studio reaches it inside the compose network. */
export const PRIMARY = 'http://artemis-primary:8161/console/jolokia';
const ACCOUNT = 'artemis';

const exact = (page: Page, label: string) => page.getByLabel(label, { exact: true });
const press = (page: Page, name: string) => page.getByRole('button', { name, exact: true }).click();

/** Presses Check connection and waits for the answer to show. */
async function check(page: Page) {
  await press(page, 'Check connection');
  await page.waitForTimeout(400);
}

/** The registration form with the broker's one URL and the account the brokers accept, nothing else. */
async function fillRegistration(page: Page, corePassword?: string) {
  await exact(page, 'Broker management URL').fill(PRIMARY);
  await exact(page, 'Username').fill(ACCOUNT);
  await exact(page, 'Password').fill(ACCOUNT);
  if (corePassword !== undefined) {
    await exact(page, 'Core username').fill(ACCOUNT);
    await exact(page, 'Core password').fill(corePassword);
  }
}

/** The registration form, on a stack with no cluster registered yet, where Check connection can succeed. */
export const REGISTER_SCENES: Scene[] = [
  {
    id: 'one-url',
    act: async (page) => {
      await exact(page, 'Broker management URL').fill(PRIMARY);
      await exact(page, 'Name').click();
    },
  },
  {
    id: 'checked',
    act: async (page) => {
      await fillRegistration(page);
      await check(page);
    },
  },
  {
    id: 'core-rejected',
    act: async (page) => {
      await fillRegistration(page, 'not-the-core-password');
      await check(page);
    },
  },
  {
    id: 'account-in-url',
    act: async (page) => {
      await exact(page, 'Broker management URL').fill(
        `http://${ACCOUNT}:${ACCOUNT}@artemis-primary:8161/console/jolokia`,
      );
      await exact(page, 'Username').fill(ACCOUNT);
      await exact(page, 'Password').fill(ACCOUNT);
      await check(page);
    },
  },
];

/** The registration form on a stack that already has the cluster: the check is refused with where it is. */
export const REGISTER_AGAIN_SCENES: Scene[] = [
  {
    id: 'already-registered',
    act: async (page) => {
      await fillRegistration(page);
      await check(page);
    },
    expectedStatus: [409],
  },
];

/** A registered cluster's Connection section. */
export const CONNECTION_SCENES: Scene[] = [
  {
    id: 'checked',
    act: async (page) => {
      await check(page);
    },
  },
  {
    id: 'new-seed-needs-password',
    act: async (page) => {
      await exact(page, 'Broker management URL').fill('http://artemis-new-host:8161/console/jolokia');
      await check(page);
    },
  },
  {
    id: 'new-seed-unreachable',
    act: async (page) => {
      await exact(page, 'Broker management URL').fill('http://artemis-new-host:8161/console/jolokia');
      await exact(page, 'Password').fill(ACCOUNT);
      await exact(page, 'Core password').fill(ACCOUNT);
      await check(page);
    },
  },
  {
    id: 'confirm-name',
    act: async (page) => {
      await exact(page, 'Password').fill(ACCOUNT);
      await check(page);
      await press(page, 'Save connection');
    },
  },
  {
    id: 'confirm-name-typed',
    act: async (page) => {
      await exact(page, 'Password').fill(ACCOUNT);
      await check(page);
      await press(page, 'Save connection');
      await page.getByRole('dialog').getByRole('textbox').fill('demo');
    },
  },
];

/** Opens a seeded team from the Teams list by its name, the link a person follows. */
async function openTeam(page: Page, name: string) {
  await page.getByRole('link', { name, exact: true }).click();
  await page.getByRole('heading', { level: 2, name, exact: true }).waitFor();
}

/** A tab of a team page; its name carries a count. */
async function openTab(page: Page, tab: string) {
  await page.getByRole('tab', { name: new RegExp(`^${tab}`) }).click();
  await page.waitForTimeout(300);
}

/**
 * The Teams list's dialogs and each tab of a team page, populated (`qa-orders`) and empty (`qa-team-empty`),
 * as qa-seed.sh makes them; and the keyboard-shortcuts help, which the header opens over any page.
 */
export const TEAMS_SCENES: Scene[] = [
  { id: 'new-team', act: (page) => press(page, 'New team') },
  {
    id: 'created',
    act: async (page) => {
      await press(page, 'New team');
      await exact(page, 'Name').fill(`qa-created-${Date.now()}`);
      await press(page, 'Create team');
      await page.getByRole('tab', { name: 'Patterns (0)' }).waitFor();
    },
  },
  ...['Patterns', 'Members', 'Shares', 'Unowned'].flatMap((tab): Scene[] => [
    {
      id: `team-${tab.toLowerCase()}`,
      act: async (page) => {
        await openTeam(page, 'qa-orders');
        await openTab(page, tab);
      },
    },
    {
      id: `team-${tab.toLowerCase()}-empty`,
      act: async (page) => {
        await openTeam(page, 'qa-team-empty');
        await openTab(page, tab);
      },
    },
  ]),
  {
    id: 'delete-team',
    act: async (page) => {
      await openTeam(page, 'qa-orders');
      await press(page, 'Delete qa-orders');
    },
  },
  { id: 'shortcuts', act: (page) => press(page, 'Keyboard shortcuts') },
];

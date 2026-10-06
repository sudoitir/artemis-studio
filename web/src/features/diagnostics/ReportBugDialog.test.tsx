import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor } from '@testing-library/react';
import { Notifications, notifications } from '@mantine/notifications';
import userEvent from '@testing-library/user-event';

import { renderAppAt, renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ReportBugDialog } from './ReportBugDialog.tsx';

const SUMMARY = {
  studioVersion: '2026.09.4',
  contractVersion: 6,
  java: '25.0.4+7 (Eclipse Adoptium)',
  os: 'Linux 6.8 (amd64)',
  database: 'PostgreSQL 17.2',
};

const TOO_LONG = 'x'.repeat(9000);

/** Opens the dialog with a description too long for a link, so the report has to go through the clipboard. */
async function openLongReport(writeText: () => Promise<void>) {
  server.use(http.get('*/api/v1/diagnostics/summary', () => HttpResponse.json(SUMMARY)));
  const open = vi.fn();
  vi.stubGlobal('open', open);
  const user = userEvent.setup();
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  renderWithProviders(
    <>
      <Notifications />
      <ReportBugDialog opened onClose={() => {}} />
    </>,
  );
  await user.type(await screen.findByRole('textbox', { name: 'Title' }), 'Queue view is empty');
  await user.click(screen.getByRole('textbox', { name: 'What happened' }));
  await user.paste(TOO_LONG);
  await user.click(screen.getByRole('button', { name: 'Open on GitHub' }));
  return open;
}

describe('ReportBugDialog', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    act(() => notifications.clean());
  });

  it('puts a report too long for a link on the clipboard, says so, and opens the issue', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    const open = await openLongReport(writeText);

    await waitFor(() => expect(open).toHaveBeenCalledOnce());
    expect(writeText).toHaveBeenCalledOnce();
    expect(await screen.findByText(/Copied the report to your clipboard/)).toBeInTheDocument();
    // The link carries the title only: the body is on the clipboard.
    expect(new URL(open.mock.calls[0][0] as string).searchParams.has('body')).toBe(false);
  });

  it('still opens the issue when the clipboard refuses the report, and says how to copy it by hand', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    const open = await openLongReport(writeText);

    await waitFor(() => expect(open).toHaveBeenCalledOnce());
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not copy the report');
    expect(alert).toHaveTextContent('The browser did not allow Studio to write to your clipboard.');
    expect(alert).toHaveTextContent('Use "Copy as Markdown" in this dialog');
  });

  it('still opens the issue when there is no clipboard at all', async () => {
    const open = await openLongReport(async () => {
      throw new TypeError('navigator.clipboard is undefined');
    });

    await waitFor(() => expect(open).toHaveBeenCalledOnce());
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not copy the report');
  });

  it('opens from the user menu and stays open once the menu closes', async () => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'viewer',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }],
        }),
      ),
      http.get('*/api/v1/diagnostics/summary', () => HttpResponse.json(SUMMARY)),
    );
    const user = userEvent.setup();
    renderAppAt('/');

    await user.click(await screen.findByRole('button', { name: 'User menu' }));
    const item = () => screen.queryByRole('menuitem', { name: 'Report a bug…' });
    await user.click(await screen.findByRole('menuitem', { name: 'Report a bug…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Report a bug' });
    // The menu is gone once its exit transition has ended: the dialog was open before that, and must still be.
    await waitFor(() => expect(item()).not.toBeInTheDocument());
    expect(dialog).toBeInTheDocument();
  });

  it('fills the environment from Studio and opens the issue only when asked', async () => {
    const requests: string[] = [];
    server.events.on('request:start', ({ request }) => requests.push(request.url));
    server.use(http.get('*/api/v1/diagnostics/summary', () => HttpResponse.json(SUMMARY)));
    const open = vi.fn();
    vi.stubGlobal('open', open);
    const user = userEvent.setup();
    renderWithProviders(<ReportBugDialog opened onClose={() => {}} />);

    await user.click(await screen.findByRole('button', { name: /Environment \(included\)/ }));
    expect(await screen.findByText(/2026\.09\.4 \(plugin contract 6\)/)).toBeInTheDocument();
    expect(open).not.toHaveBeenCalled();
    expect(requests.every((url) => new URL(url).origin === globalThis.location.origin)).toBe(true);

    await user.click(screen.getByRole('button', { name: 'Open on GitHub' }));
    expect(await screen.findByText('Give the issue a title before opening it.')).toBeInTheDocument();
    expect(open).not.toHaveBeenCalled();

    await user.type(screen.getByRole('textbox', { name: 'Title' }), 'Queue view is empty');
    await user.type(screen.getByRole('textbox', { name: 'What happened' }), 'It froze');
    await user.click(screen.getByRole('button', { name: 'Open on GitHub' }));

    await waitFor(() => expect(open).toHaveBeenCalledOnce());
    const url = new URL(open.mock.calls[0][0] as string);
    expect(url.pathname).toMatch(/\/issues\/new$/);
    expect(url.searchParams.get('title')).toBe('Queue view is empty');
    expect(url.searchParams.get('body')).toContain('It froze');
    expect(url.searchParams.get('body')).toContain('PostgreSQL 17.2');
    server.events.removeAllListeners();
  });

  it('still offers the report when Studio cannot describe itself', async () => {
    server.use(http.get('*/api/v1/diagnostics/summary', () => HttpResponse.json({ title: 'Error' }, { status: 500 })));
    renderWithProviders(<ReportBugDialog opened onClose={() => {}} />);

    expect(await screen.findByText(/could not report its versions/)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy as Markdown' })).toBeEnabled();
  });
});

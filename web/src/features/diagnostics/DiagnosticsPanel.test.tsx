import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { DiagnosticsPanel } from './DiagnosticsPanel.tsx';

const section = (key: string, title: string, content: string, redactions = 0) => ({
  key,
  title,
  fileName: `${key}.txt`,
  content,
  bytes: content.length,
  redactions,
});

const BUNDLE = {
  id: 'b1',
  createdAt: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
  sections: [
    section('about', 'About', '{ "studio" : "2026.09.4" }'),
    section('logs', 'Logs', 'started\nlogin failed password=[redacted]\nready', 1),
  ],
};

function me(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ada',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
  );
}

describe('DiagnosticsPanel', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() }));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('keeps the bundle visible but disabled without the permission, with a reachable reason', async () => {
    me(['cluster:read']);
    const user = userEvent.setup();
    renderWithProviders(<DiagnosticsPanel />);

    // The grants arrive after the first render, which offered it; once they land it is disabled.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Prepare bundle' })).toBeDisabled());
    await user.click(screen.getByRole('button', { name: 'Why preparing a support bundle is unavailable' }));
    expect(await screen.findByText(/Create support bundles/)).toHaveTextContent('diagnostics:bundle');
  });

  it('offers the bundle while the grants are still loading', () => {
    server.use(http.get('*/api/v1/auth/me', () => new Promise(() => {})));
    renderWithProviders(<DiagnosticsPanel />);
    expect(screen.getByRole('button', { name: 'Prepare bundle' })).toBeEnabled();
  });

  it('is built from sections: a support bundle h2, then the review h2 with the section as an h3', async () => {
    me(['*']);
    server.use(http.post('*/api/v1/admin/diagnostics/bundles', () => HttpResponse.json(BUNDLE, { status: 201 })));
    const user = userEvent.setup();
    renderWithProviders(<DiagnosticsPanel />);

    expect(await screen.findByRole('heading', { level: 2, name: 'Support bundle' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Prepare bundle' }));
    expect(await screen.findByRole('heading', { level: 2, name: 'Review the bundle' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'About' })).toBeInTheDocument();
    // A scrolling region takes the keyboard, so a long section can be read without a pointer.
    expect(screen.getByRole('region', { name: 'About contents' })).toHaveAttribute('tabindex', '0');
  });

  it('previews every section with its redactions and downloads only the kept ones', async () => {
    me(['*']);
    let sent: unknown = null;
    server.use(
      http.post('*/api/v1/admin/diagnostics/bundles', () => HttpResponse.json(BUNDLE, { status: 201 })),
      http.post('*/api/v1/admin/diagnostics/bundles/b1/download', async ({ request }) => {
        sent = await request.json();
        return new HttpResponse(new Blob(['zip']), {
          headers: {
            'content-type': 'application/zip',
            'content-disposition': 'attachment; filename="bundle-1.zip"',
          },
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<DiagnosticsPanel />);

    await user.click(await screen.findByRole('button', { name: 'Prepare bundle' }));
    expect(await screen.findByText('Review the bundle')).toBeInTheDocument();
    expect(screen.getByText('1 redacted')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Show Logs' }));
    const logs = screen.getByLabelText('Logs contents');
    expect(within(logs).getByText('[redacted]').tagName).toBe('MARK');

    await user.type(screen.getByRole('textbox', { name: 'Find in Logs' }), 'ready');
    await waitFor(() => expect(screen.getByLabelText('Logs contents')).toHaveTextContent(/^ready$/));
    expect(screen.getByText('1 of 3 lines')).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Include About' }));
    expect(screen.getByText(/of 2 sections/)).toHaveTextContent('1 of 2 sections');
    await user.click(screen.getByRole('button', { name: 'Download bundle' }));

    await waitFor(() => expect(sent).toEqual({ sections: ['logs'] }));
    expect(await screen.findByRole('status')).toHaveTextContent('Downloaded bundle-1.zip, recorded in the audit log.');
  });

  it('refuses a download with nothing kept, and says why', async () => {
    me(['*']);
    server.use(http.post('*/api/v1/admin/diagnostics/bundles', () => HttpResponse.json(BUNDLE, { status: 201 })));
    const user = userEvent.setup();
    renderWithProviders(<DiagnosticsPanel />);

    await user.click(await screen.findByRole('button', { name: 'Prepare bundle' }));
    await user.click(await screen.findByRole('checkbox', { name: 'Include About' }));
    await user.click(screen.getByRole('checkbox', { name: 'Include Logs' }));

    expect(screen.getByRole('button', { name: 'Download bundle' })).toBeDisabled();
    expect(screen.getByText('Keep at least one section to download.')).toBeInTheDocument();
  });

  it('turns a download of an expired snapshot into an offer to prepare again', async () => {
    me(['*']);
    server.use(
      http.post('*/api/v1/admin/diagnostics/bundles', () => HttpResponse.json(BUNDLE, { status: 201 })),
      http.post('*/api/v1/admin/diagnostics/bundles/b1/download', () =>
        HttpResponse.json({ title: 'Not Found', detail: 'Support bundle b1 does not exist.' }, { status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DiagnosticsPanel />);

    await user.click(await screen.findByRole('button', { name: 'Prepare bundle' }));
    await user.click(await screen.findByRole('button', { name: 'Download bundle' }));

    expect(await screen.findByText('This snapshot has expired')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download bundle' })).toBeDisabled();
  });

  it('states why a bundle could not be prepared', async () => {
    me(['*']);
    server.use(
      http.post('*/api/v1/admin/diagnostics/bundles', () =>
        HttpResponse.json({ title: 'Error', detail: 'Database unavailable' }, { status: 500 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DiagnosticsPanel />);

    await user.click(await screen.findByRole('button', { name: 'Prepare bundle' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Studio failed to complete the request');
    expect(alert).toHaveTextContent('Database unavailable');
    // The cause is stated with the next step, and trying again is one press.
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});

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

  it('explains the missing permission instead of offering the bundle', async () => {
    me(['cluster:read']);
    renderWithProviders(<DiagnosticsPanel />);
    expect(await screen.findByText('You cannot create support bundles')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Prepare bundle' })).not.toBeInTheDocument();
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
    expect(await screen.findByText('The bundle could not be prepared')).toBeInTheDocument();
    expect(screen.getByText('Database unavailable')).toBeInTheDocument();
  });
});

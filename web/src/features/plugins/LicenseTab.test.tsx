import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { PluginLicenseView, PluginView } from './api.ts';
import { info, me, plugin } from './fixtures.ts';
import { LICENSE_LABEL } from './words.ts';
import { PluginDrawer } from './PluginDrawer.tsx';

const DAY = 24 * 60 * 60 * 1000;

function licensed(over: Partial<PluginLicenseView> = {}): PluginView {
  return plugin({
    info: info({ requiresLicense: true }),
    license: {
      state: 'VALID',
      expiresAt: null,
      licensee: null,
      detail: null,
      uploadedAt: new Date().toISOString(),
      uploadedBy: 'ops',
      reportedAt: new Date().toISOString(),
      ...over,
    },
  });
}

const missing = () =>
  licensed({ state: 'MISSING', uploadedAt: null, uploadedBy: null, reportedAt: null, licensee: null, detail: null });

function setup(p: PluginView, props: Partial<Parameters<typeof PluginDrawer>[0]> = {}) {
  const user = userEvent.setup();
  renderWithProviders(<PluginDrawer plugin={p} canInstall onClose={vi.fn()} onUpdate={vi.fn()} {...props} />);
  return { user };
}

/** A valid license opens on the overview; the tab is one click away. */
async function openLicense(user: ReturnType<typeof userEvent.setup>) {
  const tab = await screen.findByRole('tab', { name: 'License' });
  if (tab.getAttribute('aria-selected') !== 'true') await user.click(tab);
}

function pick(file: File) {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]');
  if (!input) throw new Error('no file input');
  return userEvent.upload(input, file);
}

beforeEach(() =>
  server.use(
    me(),
    http.get('*/api/v1/admin/plugins/acme-notes/history', () => HttpResponse.json(paged([]))),
  ),
);

describe('the license tab', () => {
  it('is absent for a plugin that needs no license', async () => {
    setup(plugin());
    await screen.findByRole('dialog', { name: 'Notes 1.0.0' });
    expect(screen.queryByRole('tab', { name: 'License' })).not.toBeInTheDocument();
  });

  it('teaches what to do when there is none, and offers the upload', async () => {
    setup(missing());

    expect(await screen.findByText(/Notes needs a license file\. Ask Acme for one and upload it here\./)).toBeVisible();
    expect(screen.getByText('No license')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Upload license…' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /Remove license/ })).not.toBeInTheDocument();
  });

  it('says a stored file is waiting for the plugin to check it', async () => {
    setup(licensed({ state: 'UNCHECKED', reportedAt: null }));

    expect(await screen.findByText(/has not said what it makes of it yet/)).toBeVisible();
    expect(screen.getByText(LICENSE_LABEL.UNCHECKED)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Replace license…' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Remove license…' })).toBeEnabled();
  });

  it('shows who a valid license is for and when it ends', async () => {
    const { user } = setup(
      licensed({ licensee: 'Acme Ltd', expiresAt: new Date(Date.now() + 200.5 * DAY).toISOString() }),
    );
    await openLicense(user);

    expect(await screen.findByText('Notes accepted this license.')).toBeVisible();
    expect(screen.getByText('Acme Ltd')).toBeVisible();
    expect(screen.getByText(/\(in 200 days\)/)).toBeVisible();
    expect(screen.getByText('Uploaded').closest('tr')).toHaveTextContent(/by ops/);
  });

  it('warns that an expiring license ends soon', async () => {
    setup(licensed({ state: 'EXPIRING', expiresAt: new Date(Date.now() + 12.5 * DAY).toISOString() }));

    expect(await screen.findByText(/and it ends soon\. Ask Acme for a new one/)).toBeVisible();
    expect(screen.getByText(/\(in 12 days\)/)).toBeVisible();
  });

  it('says an expired license has ended', async () => {
    const { user } = setup(licensed({ state: 'EXPIRED', expiresAt: new Date(Date.now() - 3.5 * DAY).toISOString() }));
    await openLicense(user);

    expect(await screen.findByText('This license has ended. Ask Acme for a new one and upload it here.')).toBeVisible();
    expect(screen.getByText(/\(3 days ago\)/)).toBeVisible();
  });

  it('shows what the plugin says about a license over its limit', async () => {
    setup(licensed({ state: 'OVER_LIMIT', detail: '7 of 5 broker instances' }));

    expect(await screen.findByText(/used beyond what the license allows/)).toBeVisible();
    expect(screen.getByText('7 of 5 broker instances')).toBeVisible();
  });

  it('says a file the plugin did not accept is not accepted, and why', async () => {
    setup(licensed({ state: 'INVALID', detail: 'Signed with a key this plugin does not trust.' }));

    expect(await screen.findByText(/Notes did not accept this file\./)).toBeVisible();
    expect(screen.getByText('Signed with a key this plugin does not trust.')).toBeVisible();
  });
});

describe('uploading and removing', () => {
  it('sends the file as raw bytes after a confirmation, and says it was stored', async () => {
    let type: string | null = null;
    server.use(
      http.put('*/api/v1/admin/plugins/acme-notes/license', ({ request }) => {
        type = request.headers.get('content-type');
        return HttpResponse.json(licensed({ state: 'UNCHECKED' }).license);
      }),
    );
    const { user } = setup(missing());
    await pick(new File(['{"format":1}'], 'acme.license', { type: 'application/json' }));

    const dialog = await screen.findByRole('dialog', { name: 'Upload the license of Notes' });
    expect(within(dialog).getByText('acme.license')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Upload license' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Upload the license of Notes' })).toBeNull());
    expect(type).toBe('application/octet-stream');
    expect(await screen.findByRole('status')).toHaveTextContent('License uploaded.');
  });

  it('states a refusal in the confirmation and stores nothing', async () => {
    server.use(
      http.put('*/api/v1/admin/plugins/acme-notes/license', () =>
        HttpResponse.json(
          { title: 'Refused', detail: 'A license file may be at most 64 KiB.', violations: [] },
          { status: 413 },
        ),
      ),
    );
    const { user } = setup(licensed({ state: 'INVALID' }));
    await pick(new File(['x'], 'big.license'));

    const dialog = await screen.findByRole('dialog', { name: 'Replace the license of Notes' });
    await user.click(within(dialog).getByRole('button', { name: 'Replace license' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A license file may be at most 64 KiB.');
    expect(screen.getByRole('dialog', { name: 'Replace the license of Notes' })).toBeInTheDocument();
  });

  it('removes the license only once the plugin id is typed', async () => {
    let removed = false;
    server.use(
      http.delete('*/api/v1/admin/plugins/acme-notes/license', () => {
        removed = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user } = setup(licensed());
    await openLicense(user);
    await user.click(await screen.findByRole('button', { name: 'Remove license…' }));

    const dialog = await screen.findByRole('dialog', { name: 'Remove the license of Notes' });
    const confirm = within(dialog).getByRole('button', { name: 'Remove the license' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox'), 'acme-notes');
    await user.click(confirm);

    await waitFor(() => expect(removed).toBe(true));
  });

  it('shows the controls disabled, with the reason, to someone who cannot install', async () => {
    const { user } = setup(licensed(), { canInstall: false, cannotInstall: 'Only an installer can change this.' });
    await openLicense(user);

    expect(await screen.findByRole('button', { name: 'Replace license…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Remove license…' })).toBeDisabled();
    expect(
      within(screen.getByRole('tabpanel', { name: 'License' })).getByText('Only an installer can change this.'),
    ).toBeVisible();
  });
});

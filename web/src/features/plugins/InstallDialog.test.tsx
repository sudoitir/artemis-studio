import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { info, inventory, me, plan, plugin } from './fixtures.ts';
import { InstallDialog, type Source } from './InstallDialog.tsx';
import { holdButton } from '../../test/hold.ts';

const SHA = 'b'.repeat(64);
const upload = (over = {}) => ({ sha256: SHA, plan: plan(over), warnings: [] });

const JAR: Source = { kind: 'file', file: new File(['PK'], 'acme-notes-1.0.0.jar') };
const UPDATE: Source = { kind: 'update', id: 'acme-notes' };
const RESUME: Source = { kind: 'resume', sha: SHA };

function refusal(violations: object[]) {
  return HttpResponse.json(
    { type: 'https://artemis-studio.dev/problems/plugin-refused', title: 'Refused', detail: 'refused', violations },
    { status: 422 },
  );
}

function open(source: Source) {
  const onClose = vi.fn();
  renderWithProviders(<InstallDialog source={source} canInstall onClose={onClose} />);
  return { onClose, user: userEvent.setup() };
}

describe('InstallDialog inspection', () => {
  it('says how big the jar is while it is inspected, and that none of its code runs', async () => {
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', async () => {
        await delay('infinite');
      }),
    );
    open(JAR);

    expect(
      await screen.findByText(/Inspecting acme-notes-1\.0\.0\.jar \(1 KB\).*No code in it runs\./),
    ).toBeInTheDocument();
  });

  it('reports a large jar in megabytes', async () => {
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', async () => {
        await delay('infinite');
      }),
    );
    open({ kind: 'file', file: new File([new ArrayBuffer(3 * 1024 * 1024)], 'big.jar') });

    expect(await screen.findByText(/Inspecting big\.jar \(3\.0 MB\)/)).toBeInTheDocument();
  });

  it('downloads an update and says it is checked against the vendor checksum, then reviews it as an update', async () => {
    server.use(
      me(),
      http.post('*/api/v1/admin/plugins/acme-notes/download-update', async () => {
        await delay(50);
        return HttpResponse.json(upload({ fromVersion: '0.9.0', toVersion: '1.1.0' }), { status: 201 });
      }),
    );
    open(UPDATE);

    expect(await screen.findByText(/Downloading the update and checking it matches the checksum/)).toBeInTheDocument();
    expect(await screen.findByRole('dialog', { name: 'Update Notes to 1.1.0' })).toBeInTheDocument();
    expect(screen.getByText(/\(installed: 0\.9\.0\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
  });

  it('shows a refusal without violations as its message, with no report to copy, and stores nothing', async () => {
    server.use(
      me(),
      http.post('*/api/v1/admin/plugins/acme-notes/download-update', () =>
        HttpResponse.json({ title: 'Bad gateway', detail: 'The vendor did not answer.' }, { status: 502 }),
      ),
    );
    open(UPDATE);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Studio failed to complete the request');
    expect(alert).toHaveTextContent('The vendor did not answer.');
    expect(screen.getByText('Nothing was stored.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Copy report/ })).not.toBeInTheDocument();
  });

  it('lists every violation with its fix and copies a report for the plugin author', async () => {
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', () =>
        refusal([
          { code: 'denied-call', message: 'Calls System.exit.', fix: 'Remove the call.', severity: 'ERROR' },
          { code: 'no-fix', message: 'Odd layout.', fix: '', severity: 'ERROR' },
        ]),
      ),
    );
    const { user } = open(JAR);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Calls System.exit.');
    expect(alert).toHaveTextContent('Remove the call.');
    expect(alert).toHaveTextContent('Odd layout.');
    await user.click(within(alert).getByRole('button', { name: 'Copy report for the plugin author' }));
    expect(await within(alert).findByRole('button', { name: 'Copied' })).toBeInTheDocument();
    expect(await navigator.clipboard.readText()).toBe(
      '- [denied-call] Calls System.exit.\n  Fix: Remove the call.\n- [no-fix] Odd layout.',
    );
  });
});

describe('InstallDialog review and confirmation', () => {
  it('discards an inspected upload nobody activated when the operator cancels at review', async () => {
    const discarded = vi.fn();
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', () => HttpResponse.json(upload(), { status: 201 })),
      http.delete('*/api/v1/admin/plugins/uploads/:sha', ({ params }) => {
        discarded(params.sha);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, onClose } = open(JAR);

    await user.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(discarded).toHaveBeenCalledWith(SHA));
  });

  it('closes a refused inspection without discarding anything', async () => {
    const discarded = vi.fn();
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', () => refusal([])),
      http.delete('*/api/v1/admin/plugins/uploads/:sha', () => {
        discarded();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, onClose } = open(JAR);

    await screen.findByRole('alert');
    await user.keyboard('{Escape}');

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(discarded).not.toHaveBeenCalled();
  });

  it.each([
    [
      'a restart the server makes',
      { activationClass: 'RESTART', restart: 'AUTOMATIC' },
      'Studio restarts, disconnecting everyone briefly.',
    ],
    ['a manual restart', { activationClass: 'RESTART', restart: 'MANUAL' }, 'Nobody is interrupted.'],
    [
      'a brief pause of an installed plugin',
      { activationClass: 'BRIEF_MAINTENANCE', fromVersion: '0.9.0' },
      'Notes pauses for a few seconds.',
    ],
    ['a first install with a schema', { activationClass: 'BRIEF_MAINTENANCE' }, 'Nobody is interrupted.'],
  ] as const)('states who is interrupted by %s', async (_name, over, words) => {
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', () => HttpResponse.json(upload(over), { status: 201 })),
    );
    const { user } = open(JAR);

    await user.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(screen.getByText(new RegExp(words.replace('.', '\\.')))).toBeInTheDocument();
  });

  it('says who the change reaches: everyone, replacing a version or adding the plugin', async () => {
    server.use(
      me(),
      http.post('*/api/v1/admin/plugins/acme-notes/download-update', () =>
        HttpResponse.json(upload({ fromVersion: '0.9.0' }), { status: 201 }),
      ),
    );
    const { user } = open(UPDATE);

    await user.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(screen.getByText(/Replaces Notes 0\.9\.0 for everyone using Studio\./)).toBeInTheDocument();
  });

  it('will not arm the confirmation while a requirement is missing, even once signed in and typed', async () => {
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', () =>
        HttpResponse.json(upload({ missingRequires: ['acme-core'] }), { status: 201 }),
      ),
    );
    const { user } = open(JAR);

    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    expect(screen.getByText('It requires acme-core first.')).toBeInTheDocument();

    expect(screen.getByRole('button', { name: 'Install Notes 1.0.0' })).toBeDisabled();
    expect(screen.queryByText('Confirm it is you above first.')).not.toBeInTheDocument();
  });

  it('says why the confirmation is not armed until the operator has confirmed it is them', async () => {
    server.use(
      me(new Date(Date.now() - 60 * 60_000).toISOString()),
      http.put('*/api/v1/admin/plugins/upload', () => HttpResponse.json(upload(), { status: 201 })),
    );
    const { user } = open(JAR);

    await user.click(await screen.findByRole('button', { name: 'Continue' }));

    expect(screen.getByText('Confirm it is you above first.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Install Notes 1.0.0' })).toBeDisabled();
  });

  it('states the server reasons when an activation is refused, and leaves the dialog on the confirmation', async () => {
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', () => HttpResponse.json(upload(), { status: 201 })),
      http.post('*/api/v1/admin/plugins/uploads/:sha/activate', () =>
        refusal([
          { code: 'a', message: 'Schema check failed.', fix: '', severity: 'ERROR' },
          { code: 'b', message: 'Connection budget spent.', fix: '', severity: 'ERROR' },
        ]),
      ),
    );
    const { user } = open(JAR);

    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    await holdButton(screen.getByRole('button', { name: 'Install Notes 1.0.0' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Not activated');
    expect(within(alert).getByText('Schema check failed.')).toBeInTheDocument();
    expect(within(alert).getByText('Connection budget spent.')).toBeInTheDocument();
  });

  it('falls back to the problem message when an activation is refused without listing reasons', async () => {
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', () => HttpResponse.json(upload(), { status: 201 })),
      http.post('*/api/v1/admin/plugins/uploads/:sha/activate', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'Another activation is running.' }, { status: 409 }),
      ),
    );
    const { user } = open(JAR);

    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    await holdButton(screen.getByRole('button', { name: 'Install Notes 1.0.0' }));

    expect(await screen.findByText('Another activation is running.')).toBeInTheDocument();
  });

  it('asks for a fresh sign-in, rather than showing an error, when the server demands one', async () => {
    server.use(
      me(),
      http.put('*/api/v1/admin/plugins/upload', () => HttpResponse.json(upload(), { status: 201 })),
      http.post('*/api/v1/admin/plugins/uploads/:sha/activate', () =>
        HttpResponse.json(
          { type: 'https://artemis-studio.dev/problems/reauthentication-required', title: 'Sign in again' },
          { status: 403 },
        ),
      ),
    );
    const { user } = open(JAR);

    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    await holdButton(screen.getByRole('button', { name: 'Install Notes 1.0.0' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Install Notes 1.0.0' })).toBeEnabled());
    expect(screen.queryByText('Not activated')).not.toBeInTheDocument();
  });
});

describe('InstallDialog resuming an inspected upload', () => {
  it('reads the upload again and lands on the confirmation, since the review was done before the sign-in', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins/uploads/:sha', async () => {
        await delay(50);
        return HttpResponse.json(plan({ fromVersion: '0.9.0', toVersion: '1.1.0' }));
      }),
    );
    open(RESUME);

    expect(await screen.findByText('Reading the upload again.')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /^Update Notes to 1\.1\.0/ })).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Update Notes to 1.1.0' })).toBeInTheDocument();
  });

  it('says so when the upload is gone, instead of an empty confirmation', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins/uploads/:sha', () =>
        HttpResponse.json({ title: 'Not found', detail: 'That upload expired.' }, { status: 404 }),
      ),
    );
    open(RESUME);

    expect(await screen.findByText('That upload expired.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^(Install|Update|Activate) / })).not.toBeInTheDocument();
  });
});

describe('InstallDialog progress', () => {
  it('follows an activation to its end: closing while it runs says it carries on, and it is not discarded', async () => {
    const discarded = vi.fn();
    let status = 'activating';
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins', () =>
        HttpResponse.json(
          inventory([
            plugin({
              status,
              progress: status === 'activating' ? 'starting' : null,
              activatedAt: new Date().toISOString(),
              info: info(),
            }),
          ]),
        ),
      ),
      http.put('*/api/v1/admin/plugins/upload', () => HttpResponse.json(upload(), { status: 201 })),
      http.post('*/api/v1/admin/plugins/uploads/:sha/activate', () => HttpResponse.json(plan(), { status: 202 })),
      http.delete('*/api/v1/admin/plugins/uploads/:sha', () => {
        discarded();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { user, onClose } = open(JAR);

    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    await holdButton(screen.getByRole('button', { name: 'Install Notes 1.0.0' }));

    expect(await screen.findByText(/You can close this; it carries on/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close — it carries on' })).toBeInTheDocument();

    status = 'active';
    expect(await screen.findByText('Notes 1.0.0 is active', {}, { timeout: 5_000 })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Done' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(discarded).not.toHaveBeenCalled();
  });
});

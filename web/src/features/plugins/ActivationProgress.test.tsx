import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ActivationProgress } from './ActivationProgress.tsx';
import type { PluginsView, PluginView } from './api.ts';
import { info, inventory, plan, plugin } from './fixtures.ts';

const SECONDS_AGO = (s: number) => new Date(Date.now() - s * 1000).toISOString();

/** The inventory the progress view polls, with one plugin in the state under test. */
function serve(p: Partial<PluginView> | null, restart: Partial<PluginsView['restart']> = {}) {
  server.use(http.get('*/api/v1/admin/plugins', () => HttpResponse.json(inventory(p ? [plugin(p)] : [], restart))));
}

describe('ActivationProgress', () => {
  it('shows the step in flight with its elapsed time, and is still pending', async () => {
    serve({ status: 'activating', progress: 'migrating', stepStartedAt: SECONDS_AGO(7), activatedAt: null });
    const onOutcome = vi.fn();
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} onOutcome={onOutcome} />);

    expect(await screen.findByText(/in progress · \d+ s/)).toBeInTheDocument();
    expect(screen.getByText(/Activating Notes 1\.0\.0\. You can close this; it carries on\./)).toBeInTheDocument();
    expect(onOutcome).toHaveBeenLastCalledWith('pending');
    // An instant activation never stops the running version, so that step is not listed.
    expect(screen.getByText('Changed its database')).toBeInTheDocument();
    expect(screen.queryByText('Stopped the running version')).not.toBeInTheDocument();
  });

  it('lists the draining step only where the plugin pauses, and shows progress without a clock when none started', async () => {
    serve({ status: 'activating', progress: 'draining', stepStartedAt: null, activatedAt: null });
    renderWithProviders(
      <ActivationProgress plan={plan({ activationClass: 'BRIEF_MAINTENANCE' })} startedAt={Date.now()} />,
    );

    expect(await screen.findByText('Stopped the running version')).toBeInTheDocument();
    expect(await screen.findByText('in progress')).toBeInTheDocument();
  });

  it('says it is active once it activated since the confirmation, and offers to reload for its screens', async () => {
    serve({ status: 'active', activatedAt: new Date().toISOString() });
    const onOutcome = vi.fn();
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} onOutcome={onOutcome} />);

    expect(await screen.findByText('Notes 1.0.0 is active')).toBeInTheDocument();
    expect(screen.getByText('Reload Studio to load its screens.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload Studio' })).toBeEnabled();
    expect(onOutcome).toHaveBeenLastCalledWith('succeeded');
  });

  it('reloads the page when asked', async () => {
    serve({ status: 'active', activatedAt: new Date().toISOString() });
    const reload = vi.fn();
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, reload });
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} />);

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Reload Studio' }));
    expect(reload).toHaveBeenCalledTimes(1);
    vi.restoreAllMocks();
  });

  it('says a plugin without screens is running and has nothing to reload', async () => {
    serve({ status: 'active', activatedAt: new Date().toISOString() });
    renderWithProviders(
      <ActivationProgress
        plan={plan({
          info: info({
            contributions: { ui: false, permissions: [], settingKeys: [], streamTopics: [], mcpTools: [] },
          }),
        })}
        startedAt={Date.now()}
      />,
    );

    expect(await screen.findByText('It is running; it has no screens of its own.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reload Studio' })).not.toBeInTheDocument();
  });

  it('does not call an old activation a success: active since before this confirmation is still pending', async () => {
    serve({ status: 'active', activatedAt: SECONDS_AGO(600) });
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} />);

    expect(await screen.findByText(/You can close this; it carries on/)).toBeInTheDocument();
    expect(screen.queryByText('Notes 1.0.0 is active')).not.toBeInTheDocument();
  });

  it('is pending, not active, when an active plugin has never recorded an activation time', async () => {
    serve({ status: 'active', activatedAt: null });
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} />);

    expect(await screen.findByText(/You can close this; it carries on/)).toBeInTheDocument();
  });

  it('states why it did not start, and that the running version is untouched after an instant update', async () => {
    serve({ status: 'failed', failure: 'Schema change 0002 failed.', activatedAt: null });
    const onOutcome = vi.fn();
    renderWithProviders(
      <ActivationProgress plan={plan({ fromVersion: '0.9.0' })} startedAt={Date.now()} onOutcome={onOutcome} />,
    );

    expect(await screen.findByText('Notes 1.0.0 did not start')).toBeInTheDocument();
    expect(screen.getByText('Schema change 0002 failed.')).toBeInTheDocument();
    expect(screen.getByText('0.9.0 is still running; nothing changed for its users.')).toBeInTheDocument();
    expect(
      screen.getByText(/Upload a fixed version, or open the plugin's details to retry or remove it\./),
    ).toBeInTheDocument();
    expect(onOutcome).toHaveBeenLastCalledWith('failed');
  });

  it('gives a failure without a reason as such, and does not claim an old version runs on a first install', async () => {
    serve({ status: 'failed', failure: null, activatedAt: null });
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} />);

    expect(await screen.findByText('The server gave no reason.')).toBeInTheDocument();
    expect(screen.queryByText(/is still running/)).not.toBeInTheDocument();
  });

  it('does not claim the old version runs on when the update paused it', async () => {
    serve({ status: 'failed', failure: 'Boom.', activatedAt: null });
    renderWithProviders(
      <ActivationProgress
        plan={plan({ fromVersion: '0.9.0', activationClass: 'BRIEF_MAINTENANCE' })}
        startedAt={Date.now()}
      />,
    );

    expect(await screen.findByText('Boom.')).toBeInTheDocument();
    expect(screen.queryByText(/is still running/)).not.toBeInTheDocument();
  });

  it('says Studio is restarting itself when the restart is supervised', async () => {
    serve({ status: 'needs_restart', activatedAt: null }, { supervised: true });
    const onOutcome = vi.fn();
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} onOutcome={onOutcome} />);

    expect(await screen.findByText('Studio is restarting')).toBeInTheDocument();
    expect(screen.getByText(/This page reconnects by itself/)).toBeInTheDocument();
    expect(onOutcome).toHaveBeenLastCalledWith('restart');
  });

  it('gives the command to run when Studio cannot restart itself', async () => {
    serve({ status: 'needs_restart', activatedAt: null }, { supervised: false, command: 'systemctl restart studio' });
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} />);

    expect(await screen.findByText('Notes starts when Studio restarts')).toBeInTheDocument();
    expect(screen.getByText('systemctl restart studio')).toBeInTheDocument();
  });

  it('treats a restart in progress as a restart even while the plugin still reads as activating', async () => {
    serve({ status: 'activating', activatedAt: null }, { supervised: false, restarting: true });
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} />);

    expect(await screen.findByText('Notes starts when Studio restarts')).toBeInTheDocument();
  });

  it('reads an unreachable server as a restart, not as a failure', async () => {
    server.use(http.get('*/api/v1/admin/plugins', () => HttpResponse.json({ title: 'Forbidden' }, { status: 403 })));
    renderWithProviders(<ActivationProgress plan={plan()} startedAt={Date.now()} />);

    await waitFor(() => expect(screen.getByText('Studio is restarting')).toBeInTheDocument());
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { DisplayPreferences } from './DisplayPreferences.tsx';
import { displayZone, displayZonePreference, setDisplayZone, AUTO } from '../app/timezone.ts';

const KEY = 'as:display:timezone';

describe('DisplayPreferences', () => {
  beforeEach(() => {
    window.localStorage.clear();
    setDisplayZone(AUTO);
  });
  afterEach(() => vi.restoreAllMocks());

  it('labels the control visibly and says what it affects', () => {
    renderWithProviders(<DisplayPreferences />);

    // A placeholder is not a label (frontend rule): query by accessible name.
    expect(screen.getByRole('combobox', { name: /timezone/i })).toBeInTheDocument();
    expect(screen.getByText(/every timestamp on every screen/i)).toBeInTheDocument();
  });

  it('says it is detected from the browser before anything is chosen', () => {
    renderWithProviders(<DisplayPreferences />);

    expect(screen.getByText(/detected from this browser/i)).toBeInTheDocument();
  });

  it('pins the chosen zone and keeps it', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DisplayPreferences />);

    await user.click(screen.getByRole('combobox', { name: /timezone/i }));
    await user.click(await screen.findByText(/^UTC —/));

    await waitFor(() => expect(displayZone()).toBe('UTC'));
    expect(displayZonePreference()).toBe('UTC');
    expect(window.localStorage.getItem(KEY)).toBe('UTC');
    // The copy stops claiming the zone is being detected once it is pinned.
    expect(screen.queryByText(/detected from this browser/i)).not.toBeInTheDocument();
  });

  it('previews the current time in the selected zone', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DisplayPreferences />);

    await user.click(screen.getByRole('combobox', { name: /timezone/i }));
    await user.click(await screen.findByText(/^UTC —/));

    // The preview is the point of the control: it must show a real, zone-suffixed
    // timestamp rather than a bare IANA name the operator cannot verify.
    await waitFor(() =>
      expect(screen.getByText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}Z$/)).toBeInTheDocument(),
    );
  });

  it('is reachable and operable from the keyboard alone', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DisplayPreferences />);

    await user.tab();
    expect(screen.getByRole('combobox', { name: /timezone/i })).toHaveFocus();
  });
});

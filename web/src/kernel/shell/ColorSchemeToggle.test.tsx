import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { theme } from '../../theme.ts';
import { ColorSchemeToggle } from './ColorSchemeToggle.tsx';

const originalMatchMedia = window.matchMedia;

/** The operating system's preference, as `matchMedia` reports it. */
function systemIs(scheme: 'light' | 'dark') {
  window.matchMedia = (query: string) =>
    ({ ...originalMatchMedia(query), matches: scheme === 'dark' && query.includes('dark') }) as MediaQueryList;
}

function renderToggle() {
  return render(
    <MantineProvider theme={theme} defaultColorScheme="auto">
      <ColorSchemeToggle />
    </MantineProvider>,
  );
}

describe('ColorSchemeToggle', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.removeAttribute('data-mantine-color-scheme');
  });
  afterEach(() => {
    window.matchMedia = originalMatchMedia;
  });

  it('cycles the system, light and dark, naming the one it switches to next', async () => {
    systemIs('dark');
    const user = userEvent.setup();
    renderToggle();

    expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'dark');

    await user.click(screen.getByRole('button', { name: 'Use light theme (system is dark)' }));
    expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'light');

    await user.click(screen.getByRole('button', { name: 'Use dark theme' }));
    expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'dark');

    await user.click(screen.getByRole('button', { name: 'Use system theme' }));
    expect(screen.getByRole('button', { name: 'Use light theme (system is dark)' })).toBeInTheDocument();
  });

  it('names the scheme the system is using while it follows the system', () => {
    systemIs('light');
    renderToggle();

    expect(screen.getByRole('button', { name: 'Use light theme (system is light)' })).toBeInTheDocument();
  });
});

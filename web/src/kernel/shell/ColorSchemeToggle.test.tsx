import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { theme } from '../../theme.ts';
import { ColorSchemeToggle } from './ColorSchemeToggle.tsx';

describe('ColorSchemeToggle', () => {
  it('switches the scheme and names the one it would switch to next', async () => {
    const user = userEvent.setup();
    render(
      <MantineProvider theme={theme} defaultColorScheme="dark">
        <ColorSchemeToggle />
      </MantineProvider>,
    );

    await user.click(screen.getByRole('button', { name: 'Switch to light theme' }));

    expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'light');
    expect(screen.getByRole('button', { name: 'Switch to dark theme' })).toBeInTheDocument();
  });
});

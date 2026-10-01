import { afterEach, describe, expect, it } from 'vitest';
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Notifications, notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { McpSection } from './sections.tsx';

afterEach(() => act(() => notifications.clean()));

describe('McpConnectionPanel', () => {
  it('names the endpoint in a labelled, read-only field, and shows the client configuration under its own heading', () => {
    renderWithProviders(<McpSection />);

    const endpoint = screen.getByRole('textbox', { name: 'Endpoint' });
    expect(endpoint).toHaveAttribute('readonly');
    expect(endpoint).toHaveValue(`${globalThis.location.origin}/mcp`);
    expect(screen.getByRole('heading', { level: 3, name: 'Client configuration' })).toBeInTheDocument();
    // The key is never printed: a placeholder stands in, and the page says where to put the real one.
    expect(screen.getAllByText(/<your-api-key>/).length).toBeGreaterThan(0);
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
  });

  it('copies the endpoint from a labelled button and announces that it did', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Notifications />
        <McpSection />
      </>,
    );

    await user.click(screen.getByRole('button', { name: 'Copy endpoint' }));
    expect(await screen.findByText('Copied the endpoint')).toBeInTheDocument();
    expect(await screen.findByRole('textbox', { name: 'Endpoint' })).toHaveValue(`${globalThis.location.origin}/mcp`);
  });
});

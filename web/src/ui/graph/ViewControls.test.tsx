import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';

const flow = vi.hoisted(() => ({ zoomIn: vi.fn(), zoomOut: vi.fn(), fitView: vi.fn() }));

vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  useReactFlow: () => flow,
}));

const { ViewControls } = await import('./ViewControls.tsx');

function draw(props: Partial<React.ComponentProps<typeof ViewControls>> = {}) {
  render(
    <MantineProvider>
      <ViewControls fit={{ padding: 0.1, maxZoom: 1 }} {...props} />
    </MantineProvider>,
  );
  return userEvent.setup();
}

describe('ViewControls', () => {
  it('zooms in and out without animation', async () => {
    const user = draw();
    await user.click(screen.getByRole('button', { name: 'Zoom in' }));
    await user.click(screen.getByRole('button', { name: 'Zoom out' }));
    expect(flow.zoomIn).toHaveBeenCalledWith({ duration: 0 });
    expect(flow.zoomOut).toHaveBeenCalledWith({ duration: 0 });
  });

  it('fits the diagram the way the caller fits it, still without animation', async () => {
    const user = draw();
    await user.click(screen.getByRole('button', { name: 'Fit the diagram to the view' }));
    expect(flow.fitView).toHaveBeenCalledWith({ padding: 0.1, maxZoom: 1, duration: 0 });
  });

  it('names what it fits', () => {
    draw({ subject: 'topology' });
    expect(screen.getByRole('button', { name: 'Fit the topology to the view' })).toBeInTheDocument();
  });
});

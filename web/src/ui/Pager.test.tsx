import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { Pager } from './Pager.tsx';

describe('Pager', () => {
  it('states the position, even on a single page', () => {
    renderWithProviders(<Pager page={1} pageSize={50} total={14} onChange={vi.fn()} label="flows" />);
    expect(screen.getByText('1–14 of 14 flows')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();
  });

  it('announces where a turned page landed, and pages through the rows', async () => {
    const onChange = vi.fn();
    renderWithProviders(<Pager page={2} pageSize={50} total={120} onChange={onChange} label="flows" />);
    expect(screen.getByText('51–100 of 120 flows')).toHaveAttribute('aria-live', 'polite');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(onChange).toHaveBeenCalledWith(3);
    await userEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(onChange).toHaveBeenCalledWith(1);
  });

  it('says there are none rather than counting from zero', () => {
    renderWithProviders(<Pager page={1} pageSize={50} total={0} onChange={vi.fn()} label="flows" />);
    expect(screen.getByText('No flows')).toBeInTheDocument();
  });
});

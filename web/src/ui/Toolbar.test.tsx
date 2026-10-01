import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { Toolbar } from './Toolbar.tsx';

const css = readFileSync(resolve(process.cwd(), 'src/ui/Toolbar.module.css'), 'utf8');

const buttons = (
  <>
    <button type="button">Filter</button>
    <button type="button">Sort</button>
    <button type="button">Columns</button>
  </>
);

describe('Toolbar', () => {
  it('is a labelled group, not a toolbar, by default', () => {
    renderWithProviders(<Toolbar label="Queue filters" start={buttons} />);
    expect(screen.getByRole('group', { name: 'Queue filters' })).toBeInTheDocument();
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument();
  });

  it('keeps every control a tab stop by default', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Toolbar label="Queue filters" start={buttons} />);
    await user.tab();
    expect(screen.getByRole('button', { name: 'Filter' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Sort' })).toHaveFocus();
  });

  it('shows both slots', () => {
    renderWithProviders(
      <Toolbar label="Queue filters" start={<button type="button">Filter</button>} end={<span>12 queues</span>} />,
    );
    expect(screen.getByRole('button', { name: 'Filter' })).toBeInTheDocument();
    expect(screen.getByText('12 queues')).toBeInTheDocument();
  });

  it('wraps without a breakpoint', () => {
    expect(css).toMatch(/\.toolbar\s*{[^}]*flex-wrap:\s*wrap/);
    expect(css).not.toMatch(/@media/);
  });
});

describe('Toolbar arrowNavigation', () => {
  it('is a toolbar with a single tab stop', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Toolbar label="View controls" arrowNavigation start={buttons} />
        <button type="button">After</button>
      </>,
    );
    expect(screen.getByRole('toolbar', { name: 'View controls' })).toBeInTheDocument();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Filter' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'After' })).toHaveFocus();
  });

  it('moves between controls with the arrow keys, Home and End, wrapping at the ends', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Toolbar label="View controls" arrowNavigation start={buttons} />);
    await user.tab();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('button', { name: 'Sort' })).toHaveFocus();
    await user.keyboard('{End}');
    expect(screen.getByRole('button', { name: 'Columns' })).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('button', { name: 'Filter' })).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(screen.getByRole('button', { name: 'Columns' })).toHaveFocus();
    await user.keyboard('{Home}');
    expect(screen.getByRole('button', { name: 'Filter' })).toHaveFocus();
  });

  it('returns to the control last used when tabbed back in', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Toolbar label="View controls" arrowNavigation start={buttons} />
        <button type="button">After</button>
      </>,
    );
    await user.tab();
    await user.keyboard('{ArrowRight}');
    await user.tab();
    expect(screen.getByRole('button', { name: 'After' })).toHaveFocus();
    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Sort' })).toHaveFocus();
  });

  it('skips disabled controls and spans both slots', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Toolbar
        label="View controls"
        arrowNavigation
        start={
          <>
            <button type="button">Filter</button>
            <button type="button" disabled>
              Sort
            </button>
          </>
        }
        end={<button type="button">Columns</button>}
      />,
    );
    await user.tab();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('button', { name: 'Columns' })).toHaveFocus();
  });

  it('leaves the arrow keys to a text field', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Toolbar
        label="View controls"
        arrowNavigation
        start={
          <>
            <button type="button">Filter</button>
            <input aria-label="Search" defaultValue="abc" />
          </>
        }
      />,
    );
    await user.tab();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('textbox', { name: 'Search' })).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(screen.getByRole('textbox', { name: 'Search' })).toHaveFocus();
  });
});

import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { userEvent } from 'vitest/browser';
import { screen } from '@testing-library/react';

import { Frame, renderThemed } from '../../test/browser.tsx';
import { DataTable } from './DataTable.tsx';
import type { Column } from './columns.ts';

/**
 * A grid of 50,000 rows (operator-ui, "Large lists render in time independent of their length"): it
 * is interactive within the bound below, only the rows in view are in the page, and the keyboard,
 * selection and the grid's own find still work, since the browser's find cannot see rows that are not
 * rendered.
 */
const ROWS = 50_000;
/** From mounting to a usable grid, on a shared CI runner; the measured time is a fraction of it. */
const INTERACTIVE_MS = 3000;
/** The viewport shows about a dozen rows; the virtualizer adds its overscan and the focused row. */
const MAX_DOM_ROWS = 60;

interface Q {
  name: string;
  depth: number;
}

const data: Q[] = Array.from({ length: ROWS }, (_, i) => ({ name: `queue-${String(i).padStart(5, '0')}`, depth: i }));

const columns: Column<Q>[] = [
  { id: 'name', header: 'Queue', accessor: (r) => r.name, kind: 'identifier', priority: 'essential' },
  { id: 'depth', header: 'Depth', accessor: (r) => r.depth, kind: 'number', priority: 'high' },
];

function Large() {
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  return (
    <>
      <output data-testid="selected">{[...selected].join(',')}</output>
      <DataTable
        label="Queues"
        columns={columns}
        data={data}
        rowKey={(r) => r.name}
        empty={null}
        selectable
        selected={selected}
        onToggleRow={(key) =>
          setSelected((prev) => {
            const next = new Set(prev);
            if (!next.delete(key)) next.add(key);
            return next;
          })
        }
        height="fill"
      />
    </>
  );
}

const domRows = (grid: HTMLElement) => grid.querySelectorAll('[role="row"]').length;

async function mount() {
  const started = performance.now();
  renderThemed(
    <Frame width={960} height={480}>
      <Large />
    </Frame>,
    'light',
  );
  const grid = await screen.findByRole('grid');
  await screen.findByText('queue-00000');
  return { grid, interactiveMs: performance.now() - started };
}

describe('a grid of 50,000 rows', () => {
  it('is interactive within the bound and keeps a bounded number of rows in the page', async () => {
    const { grid, interactiveMs } = await mount();

    expect(interactiveMs).toBeLessThan(INTERACTIVE_MS);
    expect(domRows(grid)).toBeLessThan(MAX_DOM_ROWS);
    expect(grid).toHaveAttribute('aria-rowcount', String(ROWS + 1));
  });

  it('moves with the keyboard and selects a row as in a small grid', async () => {
    const { grid } = await mount();

    // The grid is one tab stop: the first body cell takes focus, then the arrow keys move it.
    grid.querySelector<HTMLElement>('[data-grid-row="1"] > [data-grid-col="1"]')!.focus();
    await userEvent.keyboard('{ArrowDown}{ArrowDown} ');
    expect(screen.getByTestId('selected')).toHaveTextContent('queue-00002');

    // Ctrl+End crosses all 50,000 rows at once; the last row is rendered and focused.
    await userEvent.keyboard('{Control>}{End}{/Control}');
    const last = await screen.findByText(`queue-${ROWS - 1}`);
    expect(last.closest('[role="row"]')).toHaveAttribute('aria-rowindex', String(ROWS + 1));
    expect(grid.contains(document.activeElement)).toBe(true);
    expect(domRows(grid)).toBeLessThan(MAX_DOM_ROWS);
  });

  it('finds a row that is not rendered, counts the matches and scrolls to it', async () => {
    const { grid } = await mount();
    expect(screen.queryByText('queue-40000')).not.toBeInTheDocument();

    grid.querySelector<HTMLElement>('[role="columnheader"][data-grid-col="1"]')!.focus();
    await userEvent.keyboard('{Control>}f{/Control}');
    await userEvent.keyboard('queue-40000');

    expect(await screen.findByText('1 of 1 match')).toBeInTheDocument();
    const found = await screen.findByText('queue-40000');
    expect(found.closest('[role="row"]')).toHaveAttribute('data-found');
    expect(domRows(grid)).toBeLessThan(MAX_DOM_ROWS);

    await userEvent.keyboard('zz');
    expect(await screen.findByText('No matches')).toBeInTheDocument();
  });
});

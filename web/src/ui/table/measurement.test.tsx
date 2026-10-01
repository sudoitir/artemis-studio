import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Column } from './columns.ts';
import { fakeLayout } from './fakeLayout.ts';
import { MiddleTruncate } from './MiddleTruncate.tsx';
import { Measurer } from './measure.tsx';
import { cellText, columnSpec, readMeasurement, sampleRows, useMeasurement } from './measurement.ts';

interface Row {
  name: string;
  depth: number;
  code: string;
}

const cols: Column<Row>[] = [
  { id: 'name', header: 'Queue', accessor: (r) => r.name, kind: 'identifier', priority: 'essential' },
  { id: 'depth', header: 'Depth', accessor: (r) => r.depth, kind: 'number', priority: 'high' },
  { id: 'code', header: 'Code', accessor: (r) => r.code, kind: 'code', priority: 'low' },
];

const rows = (count: number, name = (i: number) => `queue-${i}`): Row[] =>
  Array.from({ length: count }, (_, i) => ({ name: name(i), depth: i, code: 'x' }));

describe('columnSpec', () => {
  it('takes a column’s bounds and font from its kind, and lets the column override them', () => {
    expect(columnSpec(cols[0])).toMatchObject({ min: 16, max: 64, grow: true, truncate: 'middle', mono: false });
    expect(columnSpec(cols[1])).toMatchObject({ min: undefined, grow: false, tabular: true, alignEnd: true });
    expect(columnSpec(cols[2])).toMatchObject({ mono: true, textLike: true });
    expect(columnSpec({ ...cols[0], min: 20, max: 30, grow: false })).toMatchObject({ min: 20, max: 30, grow: false });
    expect(columnSpec({ ...cols[1], wrap: true }).truncates).toBe(true);
    expect(columnSpec(cols[1]).truncates).toBe(false);
  });
});

describe('badge columns', () => {
  const status: Column<Row> = {
    id: 'state',
    header: 'State',
    accessor: (r) => r.code,
    kind: 'status',
    priority: 'high',
  };
  const badged: Column<Row> = { ...status, badge: true };

  it('are only the columns that say so, whatever their kind', () => {
    expect(columnSpec(status).badge).toBe(false);
    expect(columnSpec(cols[0]).badge).toBe(false);
    expect(columnSpec({ ...cols[0], badge: true }).badge).toBe(true);
    expect(columnSpec({ ...status, badge: true }).badge).toBe(true);
  });

  it('has the Measurer size its values with the badge chrome, and no other column', () => {
    const fields = [cols[0], badged].map((column) => ({
      id: column.id,
      header: column.header,
      sortable: false,
      spec: columnSpec(column),
    }));
    const { container } = render(
      <Measurer fields={fields} sample={[['queue-1', 'Healthy']]} rootRef={{ current: null }} />,
    );
    const cells = Array.from(container.querySelectorAll('[data-badge]')).map((cell) => cell.getAttribute('data-text'));
    expect(cells).toEqual(['Healthy']);
  });
});

describe('cellText', () => {
  it('is the value as text, and nothing for none', () => {
    expect(cellText('a')).toBe('a');
    expect(cellText(7)).toBe('7');
    expect(cellText(7n)).toBe('7');
    expect(cellText(false)).toBe('false');
    expect(cellText(null)).toBe('');
    expect(cellText(undefined)).toBe('');
  });
});

describe('sampleRows', () => {
  it('is every row of a short page', () => {
    expect(sampleRows(cols, rows(3))).toEqual([
      ['queue-0', '0', 'x'],
      ['queue-1', '1', 'x'],
      ['queue-2', '2', 'x'],
    ]);
  });

  it('is the first 40 rows, then the 5 longest values of each text-like column and no number', () => {
    const data = rows(200, (i) => (i === 150 ? 'a-very-long-queue-name-indeed' : `queue-${i}`));
    data[160].code = 'a-long-code-value';
    const sample = sampleRows(cols, data);

    expect(sample.slice(0, 40)).toHaveLength(40);
    const extra = sample.slice(40);
    // Five longest names and five longest codes, one row each, the other cells empty.
    expect(extra).toHaveLength(10);
    expect(extra[0]).toEqual(['a-very-long-queue-name-indeed', '', '']);
    expect(extra.filter((r) => r[2] !== '')).toHaveLength(5);
    expect(extra.find((r) => r[2] === 'a-long-code-value')).toBeDefined();
    // Numbers are not sampled beyond the first rows.
    expect(extra.every((r) => r[1] === '')).toBe(true);
  });
});

describe('readMeasurement', () => {
  it('has nothing to read without the measurer', () => {
    expect(readMeasurement(null, ['a'])).toBeNull();
  });

  it('reports no widths where there is no layout, so the solver uses the bases', () => {
    const { container } = render(<Measurer fields={[]} sample={[]} rootRef={{ current: null }} />);
    const measurement = readMeasurement(container.firstElementChild as HTMLElement, ['a']);
    expect(measurement?.intrinsic).toEqual({});
    expect(measurement?.metrics.ch).toBeGreaterThan(0);
  });

  it('reads each column’s track from the header row, and the metrics from the probes', () => {
    const restore = fakeLayout(900);
    try {
      function Probe() {
        const { measurement, measurerProps } = useMeasurement({
          columns: cols,
          data: rows(2, (i) => (i === 0 ? 'queue' : 'a-longer-queue')),
          density: 'compact',
          isBusy: () => false,
        });
        return (
          <>
            <Measurer {...measurerProps} />
            <output>{JSON.stringify(measurement)}</output>
          </>
        );
      }
      render(<Probe />);
      const { metrics, intrinsic } = JSON.parse(screen.getByRole('status').textContent ?? '{}');
      expect(metrics).toEqual({ ch: 10, mono: 10, pad: 16, select: 40, actions: 44 });
      // 'a-longer-queue' is 14 characters at 10 px, plus 16 px of padding and 1 px for rounding.
      expect(intrinsic.name).toBe(14 * 10 + 16 + 1);
    } finally {
      restore();
    }
  });
});

describe('MiddleTruncate', () => {
  it('leaves a short value as it is', () => {
    const { container } = render(<MiddleTruncate text="DLQ" />);
    expect(container).toHaveTextContent('DLQ');
    expect(container.querySelector('span')).toBeNull();
  });

  it('keeps the last 12 characters out of the shrinking start', () => {
    const text = 'artemis.internal.sf.cluster-1.0f8c2a1e-77aa-4c1d';
    const { container } = render(<MiddleTruncate text={text} />);
    const [start, tail] = [...container.querySelectorAll('span > span')];

    expect(tail.textContent).toBe(text.slice(-12));
    expect(start.textContent).toBe(text.slice(0, -12));
    expect(start).toHaveAttribute('data-clip');
    expect(container).toHaveTextContent(text);
  });
});

describe('live growth', () => {
  let restore: () => void;
  beforeEach(() => {
    vi.useFakeTimers();
    restore = fakeLayout(900);
  });
  afterEach(() => {
    restore();
    vi.useRealTimers();
  });

  function Probe({
    data,
    busy = false,
    onResume,
  }: {
    data: Row[];
    busy?: boolean;
    onResume?: (fn: () => void) => void;
  }) {
    const { measurement, measurerProps, resume } = useMeasurement({
      columns: cols,
      data,
      density: 'compact',
      isBusy: () => busy,
    });
    onResume?.(resume);
    return (
      <>
        <Measurer {...measurerProps} />
        <output>{measurement.intrinsic.name}</output>
      </>
    );
  }
  const width = () => Number(screen.getByRole('status').textContent);

  it('widens columns for new rows at most every two seconds', () => {
    const { rerender } = render(<Probe data={rows(2)} />);
    const first = width();

    const longer = rows(2, () => 'a-considerably-longer-queue-name');
    rerender(<Probe data={longer} />);
    expect(width()).toBe(first);

    act(() => void vi.advanceTimersByTime(1999));
    expect(width()).toBe(first);
    act(() => void vi.advanceTimersByTime(1));
    expect(width()).toBeGreaterThan(first);
  });

  it('never narrows a column for new rows', () => {
    const { rerender } = render(<Probe data={rows(2, () => 'a-considerably-longer-queue-name')} />);
    const first = width();
    rerender(<Probe data={rows(2, () => 'q')} />);
    act(() => void vi.advanceTimersByTime(5000));
    expect(width()).toBe(first);
  });

  it('holds growth back while the viewer is in the table, and applies it when they leave', () => {
    let resume = () => {};
    const grab = (fn: () => void) => {
      resume = fn;
    };
    const { rerender } = render(<Probe data={rows(2)} busy onResume={grab} />);
    const first = width();

    rerender(<Probe data={rows(2, () => 'a-considerably-longer-queue-name')} busy onResume={grab} />);
    act(() => void vi.advanceTimersByTime(5000));
    expect(width()).toBe(first);

    rerender(<Probe data={rows(2, () => 'a-considerably-longer-queue-name')} busy={false} onResume={grab} />);
    act(() => resume());
    expect(width()).toBeGreaterThan(first);
  });
});

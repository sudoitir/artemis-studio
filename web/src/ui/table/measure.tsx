import { memo, type CSSProperties, type RefObject } from 'react';

import classes from './DataTable.module.css';
import type { ColumnSpec, MeasureColumn } from './measurement.ts';

function boundsOf(spec: ColumnSpec): CSSProperties {
  return {
    minInlineSize: spec.min === undefined ? undefined : `${spec.min}ch`,
    maxInlineSize: spec.max === undefined ? undefined : `${spec.max}ch`,
  };
}

function cellClass(spec: ColumnSpec): string {
  return [classes.measureCell, spec.mono ? classes.mono : '', spec.tabular ? classes.tabular : '']
    .filter(Boolean)
    .join(' ');
}

interface MeasurerProps {
  fields: MeasureColumn[];
  sample: string[][];
  rootRef: RefObject<HTMLDivElement | null>;
}

/**
 * Where column widths come from (ADR-0161): an `inert`, `aria-hidden`, zero-size box, clipped so it
 * can never widen the page, holding a `max-content` grid of the header and a sample of the values.
 * Each cell is in its kind's font and bounded in `ch`, so a track's width is already clamped, and
 * because the grid does not depend on the table's own width, what it reports cannot lock a column at
 * the width it was stretched to.
 */
export const Measurer = memo(function Measurer({ fields, sample, rootRef }: Readonly<MeasurerProps>) {
  return (
    <div ref={rootRef} className={classes.measurer} aria-hidden="true" inert>
      <div className={classes.measureGrid} style={{ gridTemplateColumns: `repeat(${fields.length}, max-content)` }}>
        {fields.map((field) => (
          <div
            key={field.id}
            data-measure-head
            className={`${classes.measureCell} ${classes.measureHead}`}
            data-text={field.header}
            style={boundsOf(field.spec)}
          >
            {field.sortable ? <span className={classes.sortMark} /> : null}
          </div>
        ))}
        {sample.map((row, r) =>
          row.map((text, i) => (
            <div
              key={`${r}:${fields[i].id}`}
              className={cellClass(fields[i].spec)}
              data-text={text}
              data-badge={fields[i].spec.badge || undefined}
              style={boundsOf(fields[i].spec)}
            />
          )),
        )}
      </div>
      <div data-probe="ch" className={classes.probe} style={{ inlineSize: '100ch' }} />
      <div data-probe="mono" className={`${classes.probe} ${classes.mono}`} style={{ inlineSize: '100ch' }} />
      <div data-probe="pad" className={classes.measureCell} />
      <div data-probe="select" className={classes.probe} style={{ inlineSize: 'var(--as-select-w)' }} />
      <div data-probe="actions" className={classes.probe} style={{ inlineSize: 'var(--as-actions-w)' }} />
    </div>
  );
});

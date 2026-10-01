import type { ReactNode } from 'react';

import classes from './DescriptionList.module.css';

/** One term and its value; `hint` is a dimmed second line under the value. */
export type DescriptionItem = Readonly<{
  /** Also the item's key, so it is unique within the list. */
  term: string;
  value: ReactNode;
  hint?: ReactNode;
}>;

/**
 * Terms and their values, as a native description list. It replaces a two-column key and value
 * table: the terms share one column and the values wrap instead of overflowing. `columns={2}` sets
 * two term and value pairs side by side for a short list in a wide space. A `label` names the list
 * for assistive technology.
 */
export function DescriptionList({
  items,
  columns = 1,
  label,
}: Readonly<{
  items: readonly DescriptionItem[];
  columns?: 1 | 2;
  label?: string;
}>) {
  const list = (
    <dl className={classes.list} data-columns={columns}>
      {items.map((item) => (
        <DescriptionRow key={item.term} item={item} />
      ))}
    </dl>
  );
  return label ? (
    <div role="group" aria-label={label}>
      {list}
    </div>
  ) : (
    list
  );
}

function DescriptionRow({ item }: Readonly<{ item: DescriptionItem }>) {
  return (
    <>
      <dt className={classes.term}>{item.term}</dt>
      <dd className={classes.value}>
        {item.value}
        {item.hint ? <div className={classes.hint}>{item.hint}</div> : null}
      </dd>
    </>
  );
}

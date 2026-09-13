import { useState } from 'react';
import { Button, Text } from '@mantine/core';

import classes from './Configuration.module.css';
import type { Row } from './pretty.ts';

/**
 * A compact key/value list for a table cell. Long lists fold after `limit`
 * rows behind a button that says how many more there are — the cell stays
 * scannable and nothing is hidden without a count.
 */
export function KeyValueList({ rows, limit = 6, empty = '—' }: { rows: Row[]; limit?: number; empty?: string }) {
  const [open, setOpen] = useState(false);
  if (rows.length === 0) {
    return (
      <Text size="xs" c="dimmed">
        {empty}
      </Text>
    );
  }
  const shown = open ? rows : rows.slice(0, limit);
  const hidden = rows.length - shown.length;
  return (
    <div>
      <dl className={classes.kv}>
        {shown.map((r) => (
          <div key={r.key} className={classes.kvRow}>
            <dt className={classes.kvKey}>{r.key}</dt>
            <dd className={classes.kvValue}>{r.value}</dd>
          </div>
        ))}
      </dl>
      {hidden > 0 || open ? (
        <Button variant="subtle" size="compact-xs" px={0} onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          {open ? 'Show fewer' : `${hidden} more ${hidden === 1 ? 'key' : 'keys'}`}
        </Button>
      ) : null}
    </div>
  );
}

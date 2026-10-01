import { Button, Group, Text } from '@mantine/core';

import classes from './Pager.module.css';

/**
 * Previous/Next over a server-side page, with the position stated.
 *
 * One implementation rather than one per view: a paged view that does not say
 * where it is leaves the operator unable to tell whether they are looking at
 * everything, which is exactly the failure ADR-0056 is about.
 *
 * Rendered even on a single page, because "1–14 of 14" is the sentence that
 * settles the question; hiding it leaves the same doubt a missing pager does. The Previous and Next
 * buttons only exist from the second page on, and the pager holds their height either way, so the
 * rows below it do not move as the total grows past one page or shrinks back.
 */
export function Pager({
  page,
  pageSize,
  total,
  onChange,
  label,
}: Readonly<{
  /** 1-based. */
  page: number;
  pageSize: number;
  total: number;
  onChange: (page: number) => void;
  /** Plural noun for the rows, e.g. `flows`. */
  label: string;
}>) {
  const lastPage = Math.max(1, Math.ceil(total / pageSize));
  const first = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const last = Math.min(page * pageSize, total);

  return (
    <Group justify="space-between" gap="xs" className={classes.pager}>
      {/* Polite, so a page turned with the keyboard says where it landed. */}
      <Text size="xs" c="dimmed" className={classes.position} aria-live="polite">
        {total === 0 ? `No ${label}` : `${first}–${last} of ${total} ${label}`}
      </Text>
      {lastPage > 1 ? (
        <Group gap="xs">
          <Button size="xs" variant="default" disabled={page <= 1} onClick={() => onChange(page - 1)}>
            Previous
          </Button>
          <Button size="xs" variant="default" disabled={page >= lastPage} onClick={() => onChange(page + 1)}>
            Next
          </Button>
        </Group>
      ) : null}
    </Group>
  );
}

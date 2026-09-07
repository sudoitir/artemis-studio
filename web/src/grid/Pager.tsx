import { Button, Group, Text } from '@mantine/core';

/**
 * Previous/Next over a server-side page, with the position stated.
 *
 * One implementation rather than one per view: a paged view that does not say
 * where it is leaves the operator unable to tell whether they are looking at
 * everything, which is exactly the failure ADR-0056 is about.
 *
 * Rendered even on a single page, because "1–14 of 14" is the sentence that
 * settles the question; hiding it leaves the same doubt a missing pager does.
 */
export function Pager({
  page,
  pageSize,
  total,
  onChange,
  label,
}: {
  /** 1-based. */
  page: number;
  pageSize: number;
  total: number;
  onChange: (page: number) => void;
  /** Plural noun for the rows, e.g. `flows`. */
  label: string;
}) {
  const lastPage = Math.max(1, Math.ceil(total / pageSize));
  const first = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const last = Math.min(page * pageSize, total);

  return (
    <Group justify="space-between" gap="xs">
      <Text size="xs" c="dimmed">
        {total === 0 ? `No ${label}` : `${first}–${last} of ${total} ${label}`}
      </Text>
      {lastPage > 1 ? (
        <Group gap="xs">
          <Button
            size="xs"
            variant="default"
            disabled={page <= 1}
            onClick={() => onChange(page - 1)}
          >
            Previous
          </Button>
          <Button
            size="xs"
            variant="default"
            disabled={page >= lastPage}
            onClick={() => onChange(page + 1)}
          >
            Next
          </Button>
        </Group>
      ) : null}
    </Group>
  );
}

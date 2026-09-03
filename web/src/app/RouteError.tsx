import { Alert } from '@mantine/core';

/** Per-route crash isolation — a failed view does not blank the whole shell (frontend guide §2). */
export function RouteError({ error }: { error: Error }) {
  return (
    <Alert color="red" variant="light" title="This view failed to load">
      {error.message}
    </Alert>
  );
}

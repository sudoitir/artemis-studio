import { Alert } from '@mantine/core';

/**
 * Per-route crash isolation — a failed view does not blank the whole shell
 * (frontend guide §2). The router hands over whatever was thrown, which need
 * not be an `Error`.
 */
export function RouteError({ error }: { error: unknown }) {
  const message = error instanceof Error ? error.message : String(error);
  return (
    <Alert color="red" variant="light" title="This view failed to load">
      {message}
    </Alert>
  );
}

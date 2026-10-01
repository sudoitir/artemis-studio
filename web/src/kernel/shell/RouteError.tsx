import { Button } from '@mantine/core';

import { ApiError } from '../api/request.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';

/**
 * Per-route crash isolation — a failed view does not blank the whole shell
 * (frontend guide §2). The router hands over whatever was thrown, which need
 * not be an `Error`, and a `reset` that renders the route again.
 *
 * <p>A failed request is read by {@link ErrorState}, which names its cause and next step. Anything
 * else is a defect in the view itself: its message, and a way to try again.
 */
export function RouteError({ error, reset }: Readonly<{ error: unknown; reset?: () => void }>) {
  const message = (error instanceof Error ? error.message : String(error)).replace(/\.$/, '');
  return (
    <Page>
      <PageHeader title="This view failed to load" description="The rest of the console is unaffected." />
      {error instanceof ApiError ? (
        <ErrorState error={error} onRetry={reset} />
      ) : (
        <EmptyState
          kind="empty"
          title="The view stopped unexpectedly"
          description={`${message}. Try again; if it keeps happening, report this message to an administrator.`}
          action={
            reset ? (
              <Button variant="default" size="xs" onClick={reset}>
                Try again
              </Button>
            ) : undefined
          }
        />
      )}
    </Page>
  );
}

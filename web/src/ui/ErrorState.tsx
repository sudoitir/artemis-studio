import type { ReactNode } from 'react';
import { Button, Text } from '@mantine/core';

import classes from './ErrorState.module.css';
import { readError } from './errorReading.tsx';

/**
 * A failure shown in place of the content that could not load. It reads the error by its shape (see
 * `readError`), so it names the cause and gives the next step instead of a generic "Request failed": a
 * missing permission is named, a validation failure lists its fields, a rate limit says how long to
 * wait, a server error quotes the request id and a broker failure says which of its connection
 * problems it is. It is an alert, so it is announced when it appears.
 *
 * <p>A broker failure keeps the server's own `detail` under the cause the kind maps to, so what the
 * broker actually said is never lost to the mapping.
 *
 * <p>`onRetry` adds a Retry button when trying again can help; where it cannot (a refused
 * credential, a missing permission), the button is left out. `next` replaces the next-step sentence
 * where the caller knows a better one for its screen, and `actions` adds controls beside Retry, such
 * as a link to the page that fixes it.
 */
export function ErrorState({
  error,
  onRetry,
  next,
  actions,
  variant = 'block',
}: Readonly<{
  /** The thrown error, typically an `ApiError`; read structurally, so any value is safe. */
  error: unknown;
  /** Called by the Retry button. Omit it where retrying makes no sense. */
  onRetry?: () => void;
  /** The next step, replacing the one read from the error. */
  next?: ReactNode;
  /** Further next-step controls, after Retry. */
  actions?: ReactNode;
  /** `block` is a panel standing in for a view; `inline` is one wrapping line inside a section. */
  variant?: 'block' | 'inline';
}>) {
  const reading = readError(error);
  return (
    <div className={classes.root} role="alert" data-variant={variant}>
      <div className={classes.title}>{reading.title}</div>
      <Text size="sm" component="div" className={classes.text}>
        {reading.cause}
      </Text>
      {reading.detail && reading.detail !== reading.cause ? (
        <Text size="sm" component="div" className={classes.text}>
          {reading.detail}
        </Text>
      ) : null}
      {reading.fields?.length ? (
        <ul className={classes.fields}>
          {reading.fields.map(({ field, message }) => (
            <li key={`${field}:${message}`}>
              <strong>{field}</strong>: {message}
            </li>
          ))}
        </ul>
      ) : null}
      <Text size="sm" component="div" className={classes.text}>
        {next ?? reading.next}
      </Text>
      {reading.requestId ? (
        <Text size="xs" component="div" className={classes.requestId}>
          Request id: {reading.requestId}
        </Text>
      ) : null}
      {(reading.retry && onRetry) || actions ? (
        <div className={classes.actions}>
          {reading.retry && onRetry ? (
            <Button variant="default" size="xs" onClick={onRetry}>
              Retry
            </Button>
          ) : null}
          {actions}
        </div>
      ) : null}
    </div>
  );
}

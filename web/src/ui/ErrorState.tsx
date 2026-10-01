import type { ReactNode } from 'react';
import { Button, Text } from '@mantine/core';

import classes from './ErrorState.module.css';

/** What the failure is, why, and what the operator can do about it. */
interface Reading {
  title: string;
  cause: string;
  next: ReactNode;
  /** Whether trying again can help without the operator changing something first. */
  retry: boolean;
  fields?: readonly { field: string; message: string }[];
  requestId?: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;
const text = (value: unknown): string | undefined => (typeof value === 'string' && value ? value : undefined);

/** Broker failures, by `brokerErrorKind`. The kind wins over the status, which only says who was at fault. */
const BROKER: Readonly<Record<string, Omit<Reading, 'requestId'>>> = {
  UNREACHABLE: {
    title: 'The broker is unreachable',
    cause: 'Nothing answered at the broker address.',
    next: 'Check that the broker is running and that Studio can reach its address, then retry.',
    retry: true,
  },
  UNAUTHORIZED: {
    title: 'The broker rejected the credentials',
    cause: 'The broker refused the user name and password Studio holds for it.',
    next: "Update the cluster's credentials, then retry.",
    retry: false,
  },
  NOT_ARTEMIS: {
    title: 'There is no Artemis broker at this agent',
    cause: 'A Jolokia agent answered, but no Artemis broker is registered on it.',
    next: 'Point the cluster at the Jolokia agent of an Artemis broker.',
    retry: false,
  },
  WRONG_PATH: {
    title: 'There is no Jolokia agent at this address',
    cause: 'The address answered, but not with a Jolokia agent.',
    next: 'Check the path. The Artemis console usually serves Jolokia at /console/jolokia.',
    retry: false,
  },
  TLS_FAILED: {
    title: 'The TLS handshake with the broker failed',
    cause: 'Studio could not establish a secure connection to the broker.',
    next: "Check the broker's certificate and the cluster's TLS settings, then retry.",
    retry: false,
  },
  BAD_RESPONSE: {
    title: 'The broker sent an unexpected response',
    cause: 'The broker answered in a way Studio does not understand.',
    next: "Retry. If it keeps happening, check the broker's logs.",
    retry: true,
  },
  UNSUPPORTED_VERSION: {
    title: 'This Artemis version is not supported',
    cause: 'Studio does not support the version this broker runs.',
    next: 'Connect a broker on a supported Artemis version.',
    retry: false,
  },
};

function seconds(n: number): string {
  return `${n} ${n === 1 ? 'second' : 'seconds'}`;
}

/**
 * Reads an error by its shape. An `ApiError` carries `status`, `brokerErrorKind`, `fieldErrors` and
 * the whole problem body as `problem` (`detail`, `permission`, `retryAfter`, `requestId`); anything
 * else, such as a failed fetch, has no status and reads as the network being down.
 */
function read(error: unknown): Reading {
  const e = isRecord(error) ? error : {};
  const problem = isRecord(e.problem) ? e.problem : {};
  const status = typeof e.status === 'number' ? e.status : 0;
  const detail = text(problem.detail);
  const kind = text(e.brokerErrorKind);
  const broker = kind ? BROKER[kind] : undefined;
  if (broker) return broker;
  const retryAfter = typeof problem.retryAfter === 'number' && problem.retryAfter > 0 ? problem.retryAfter : undefined;
  const fields = Array.isArray(e.fieldErrors) ? (e.fieldErrors as Reading['fields']) : undefined;
  if (status === 0) {
    return {
      title: 'Studio could not be reached',
      cause: 'The request got no answer. The network may be down, or Studio may be restarting.',
      next: 'Check your connection, then retry.',
      retry: true,
    };
  }
  if (status === 401) {
    return {
      title: 'You are signed out',
      cause: 'Your session ended or was never started.',
      next: (
        <Button component="a" href="/login" variant="default" size="xs">
          Sign in again
        </Button>
      ),
      retry: false,
    };
  }
  if (status === 403) {
    const permission = text(problem.permission);
    return {
      title: 'You are not allowed to do this',
      cause: permission ? `Your role does not include the ${permission} permission.` : 'Your role does not allow it.',
      next: permission
        ? `Ask an administrator to grant ${permission}.`
        : 'Ask an administrator to grant the permission it needs.',
      retry: false,
    };
  }
  if (status === 404) {
    return {
      title: 'Not found',
      cause: detail ?? 'It does not exist, or it was removed.',
      next: 'Check the address, or go back to the list and choose it again.',
      retry: false,
    };
  }
  if (status === 409) {
    return {
      title: 'This conflicts with the current state',
      cause: detail ?? 'Something changed, or it conflicts with something that already exists.',
      next: 'Reload to see the current state, then try again.',
      retry: true,
    };
  }
  if (status === 422) {
    return {
      title: 'Some values are not valid',
      cause: detail ?? 'Studio could not accept the request as sent.',
      next: fields?.length
        ? 'Correct the fields listed here, then submit again.'
        : 'Correct the request, then try again.',
      retry: false,
      fields,
    };
  }
  if (status === 429) {
    return {
      title: 'Too many requests',
      cause: detail ?? 'Studio is limiting how fast requests can be made.',
      next: retryAfter ? `Wait ${seconds(retryAfter)}, then retry.` : 'Wait a moment, then retry.',
      retry: true,
    };
  }
  if (status >= 500) {
    return {
      title: 'Studio failed to complete the request',
      cause: 'An error occurred on the server.',
      next: 'Retry. If it keeps happening, report it with the request id.',
      retry: true,
      requestId: text(problem.requestId),
    };
  }
  return {
    title: text(e.title) ?? 'The request failed',
    cause: detail ?? `Studio refused the request (status ${status}).`,
    next: 'Check the request, then try again.',
    retry: false,
  };
}

/**
 * A failure shown in place of the content that could not load. It reads the error by its shape (see
 * `read`), so it names the cause and gives the next step instead of a generic "Request failed": a
 * missing permission is named, a validation failure lists its fields, a rate limit says how long to
 * wait, a server error quotes the request id and a broker failure says which of its connection
 * problems it is. It is an alert, so it is announced when it appears.
 *
 * <p>`onRetry` adds a Retry button when trying again can help; where it cannot (a refused
 * credential, a missing permission), the button is left out.
 */
export function ErrorState({
  error,
  onRetry,
  variant = 'block',
}: Readonly<{
  /** The thrown error, typically an `ApiError`; read structurally, so any value is safe. */
  error: unknown;
  /** Called by the Retry button. Omit it where retrying makes no sense. */
  onRetry?: () => void;
  /** `block` is a panel standing in for a view; `inline` is one wrapping line inside a section. */
  variant?: 'block' | 'inline';
}>) {
  const reading = read(error);
  return (
    <div className={classes.root} role="alert" data-variant={variant}>
      <div className={classes.title}>{reading.title}</div>
      <Text size="sm" component="div" className={classes.text}>
        {reading.cause}
      </Text>
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
        {reading.next}
      </Text>
      {reading.requestId ? (
        <Text size="xs" component="div" className={classes.requestId}>
          Request id: {reading.requestId}
        </Text>
      ) : null}
      {reading.retry && onRetry ? (
        <Button variant="default" size="xs" onClick={onRetry}>
          Retry
        </Button>
      ) : null}
    </div>
  );
}

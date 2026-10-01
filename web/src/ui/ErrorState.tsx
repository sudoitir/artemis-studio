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
  /** What the server said about this failure, when a mapping's own words win and would otherwise drop it. */
  detail?: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;
const text = (value: unknown): string | undefined => (typeof value === 'string' && value ? value : undefined);

/** Broker failures, by `brokerErrorKind`. The kind wins over the status, which only says who was at fault. */
const BROKER: Readonly<Record<string, Omit<Reading, 'requestId' | 'detail'>>> = {
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
 * Whether the request never got an answer: an explicit status 0, or the `TypeError` `fetch` throws
 * when the network fails (its message differs by browser). Any other `TypeError`, such as a render
 * crash, is the console's own bug, not the network.
 */
function isNetworkFailure(error: unknown, status: number | undefined): boolean {
  if (status === 0) return true;
  return error instanceof TypeError && /fetch|network|load failed/i.test(error.message);
}

/** What `read` takes from an error's problem body, for the status readings to quote. */
interface Problem {
  status: number;
  title?: string;
  detail?: string;
  permission?: string;
  requestId?: string;
  retryAfter?: number;
  fields?: NonNullable<Reading['fields']>;
}

const NETWORK_DOWN: Reading = {
  title: 'Studio could not be reached',
  cause: 'The request got no answer. The network may be down, or Studio may be restarting.',
  next: 'Check your connection, then retry.',
  retry: true,
};

function unexpected(error: unknown): Reading {
  return {
    title: 'An unexpected error occurred in the console',
    cause:
      (error instanceof Error ? text(error.message) : undefined) ?? 'The console failed in a way it did not expect.',
    next: 'Retry, or reload the page. If it keeps happening, report the message above.',
    retry: true,
  };
}

function forbidden({ permission }: Problem): Reading {
  return {
    title: 'You are not allowed to do this',
    cause: permission ? `Your role does not include the ${permission} permission.` : 'Your role does not allow it.',
    next: permission
      ? `Ask an administrator to grant ${permission}.`
      : 'Ask an administrator to grant the permission it needs.',
    retry: false,
  };
}

function invalid({ detail, fields }: Problem): Reading {
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

function rateLimited({ detail, retryAfter }: Problem): Reading {
  return {
    title: 'Too many requests',
    cause: detail ?? 'Studio is limiting how fast requests can be made.',
    next: retryAfter ? `Wait ${seconds(retryAfter)}, then retry.` : 'Wait a moment, then retry.',
    retry: true,
  };
}

function serverFailure({ detail, requestId }: Problem): Reading {
  return {
    title: 'Studio failed to complete the request',
    cause: detail ?? 'An error occurred on the server.',
    next: 'Retry. If it keeps happening, report it with the request id.',
    retry: true,
    requestId,
  };
}

function refused({ title, detail, status }: Problem): Reading {
  return {
    title: title ?? 'The request failed',
    cause: detail ?? `Studio refused the request (status ${status}).`,
    next: 'Check the request, then try again.',
    retry: false,
  };
}

/** The readings of the statuses that have their own; any other 5xx is a server failure, any other status a refusal. */
const BY_STATUS: Readonly<Record<number, (problem: Problem) => Reading>> = {
  401: () => ({
    title: 'You are signed out',
    cause: 'Your session ended or was never started.',
    next: (
      <Button component="a" href="/login" variant="default" size="xs">
        Sign in again
      </Button>
    ),
    retry: false,
  }),
  403: forbidden,
  404: ({ detail }) => ({
    title: 'Not found',
    cause: detail ?? 'It does not exist, or it was removed.',
    next: 'Check the address, or go back to the list and choose it again.',
    retry: false,
  }),
  409: ({ detail }) => ({
    title: 'This conflicts with the current state',
    cause: detail ?? 'Something changed, or it conflicts with something that already exists.',
    next: 'Reload to see the current state, then try again.',
    retry: true,
  }),
  422: invalid,
  429: rateLimited,
};

/** The parts of an error's problem body that a reading quotes. */
function problemOf(e: Record<string, unknown>, status: number): Problem {
  const problem = isRecord(e.problem) ? e.problem : {};
  const { retryAfter } = problem;
  return {
    status,
    title: text(e.title),
    detail: text(problem.detail),
    permission: text(problem.permission),
    requestId: text(problem.requestId),
    retryAfter: typeof retryAfter === 'number' && retryAfter > 0 ? retryAfter : undefined,
    fields: Array.isArray(e.fieldErrors) ? (e.fieldErrors as Reading['fields']) : undefined,
  };
}

/**
 * Reads an error by its shape. An `ApiError` carries `status`, `brokerErrorKind`, `fieldErrors` and
 * the whole problem body as `problem` (`detail`, `permission`, `retryAfter`, `requestId`); a failed
 * fetch reads as the network being down; anything else with no status is an error inside the console.
 */
function read(error: unknown): Reading {
  const e = isRecord(error) ? error : {};
  const given = typeof e.status === 'number' ? e.status : undefined;
  const kind = text(e.brokerErrorKind);
  const broker = kind ? BROKER[kind] : undefined;
  if (broker) {
    const problem = isRecord(e.problem) ? e.problem : {};
    return { ...broker, detail: text(problem.detail) };
  }
  if (isNetworkFailure(error, given)) return NETWORK_DOWN;
  if (given === undefined) return unexpected(error);
  const reading = BY_STATUS[given] ?? (given >= 500 ? serverFailure : refused);
  return reading(problemOf(e, given));
}

/**
 * A failure shown in place of the content that could not load. It reads the error by its shape (see
 * `read`), so it names the cause and gives the next step instead of a generic "Request failed": a
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
  const reading = read(error);
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

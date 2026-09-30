# ADR-0133: Credentials are redacted at the choke points

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Artemis Studio maintainers

## Context

Keeping secrets out of logs, audit rows, error bodies and exports has depended on each caller
remembering to leave them out. ADR-0092 closed six named paths for bridge credentials. Nothing
catches a credential that arrives by another route, such as:
- a broker URL with user information in an exception message;
- a password in a request that fails validation;
- an audit parameter somebody adds later.

## Decision

- One `SecretRedactor`, in the `kernel.core` module so that `Problems` can use it, recognises credential-like values and replaces them with `[redacted]`:
  - values of keys named like password, secret, token, API key, authorization, credential or
    private key;
  - bearer and basic authorization values;
  - user information in URLs;
  - PEM private keys;
  - Studio API tokens.
- It is applied where every output passes through:
  - **Logs:** a logback conversion rule for the message and the stack trace. Studio logs to the
    console only and sets no log file, so the console appender is the one output.
  - **Audit:** `AuditService` applies every `AuditParamsFilter` in order, and a credential filter
    is one of them.
  - **Errors:** the `Problems` factory, which every exception advice uses.
  - **Export:** the broker.xml export, as a backstop to ADR-0092.
- A test plants known secrets, exercises each of these outputs, and asserts that none of the
  secrets appears. That test, not the patterns, is the proof.

## Consequences

- A new code path that logs, audits or fails is covered without its author doing anything.
- Pattern matching can over-redact an innocent value, such as a field named `token` that holds a
  count. Over-redaction is the accepted failure mode.
- Pattern matching can miss a new credential shape. ADR-0092's explicit paths and the leak test
  remain the guard, and a new shape is added to the redactor with a test.

## Alternatives considered

- **Tracking every decrypted plaintext and masking exact matches.** This catches any shape, but
  it keeps plaintexts alive in memory for the process lifetime and costs a scan of every log
  line. Rejected.
- **Relying on callers alone.** This is today's state. The leak it permits is silent. Rejected.

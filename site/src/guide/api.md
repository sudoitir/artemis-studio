---
title: The REST API
description: How the Artemis Studio REST API is versioned, how breaking changes and deprecations are announced, and the conventions every endpoint follows.
---

# The REST API

Everything the web UI does goes through the REST API under `/api/v1`. The OpenAPI document is served at
`/v3/api-docs`, and each release attaches it, so a client can be generated from the version it talks to.
`info.version` in that document is the Studio version that served it.

## Versioning

The version is the second path segment: `/api/v1/clusters`. Only `v1` exists. Any other version, such as
`/api/v2/clusters`, is answered with `400` and the problem type `invalid-api-version`. A future incompatible
endpoint is added beside the `v1` one under a new version, and the `v1` endpoint keeps working until it is
deprecated and removed as described below.

The plugin gateway (`/api/v1/p/**` and `/api/v1/clusters/*/p/**`) belongs to each plugin and is not part of
this document.

## Breaking changes

A change that breaks the OpenAPI contract, such as removing a path or a field or making a field required,
must be marked in its commit with `!` after the type or a `BREAKING CHANGE:` footer. CI compares the
document with the latest release and fails a pull request that breaks it without the marker. The same
marker puts the change under **Breaking** in the release notes, so read those before you upgrade.

## Deprecation policy

- **Before the stable release**, breaking changes are allowed. They carry the marker and nothing more.
- **From the stable release**, an endpoint is removed only after it has been deprecated for an announced
  period. The announcement is in the release notes and in the API itself:
  - responses from a deprecated endpoint carry `Deprecation` ([RFC 9745](https://www.rfc-editor.org/rfc/rfc9745)),
    `Sunset` ([RFC 8594](https://www.rfc-editor.org/rfc/rfc8594)) and `Link: <...>; rel="deprecation"` headers;
  - the operation is marked `deprecated: true` in the OpenAPI document, with the sunset date in its description.

Nothing is deprecated today.

## Lists

A list endpoint answers with an envelope, never a bare array:

```json
{ "data": [], "page": 1, "pageSize": 50, "count": 0, "hasNext": false }
```

`page` is 1-based and `size` defaults to 50, at most 500. A value outside that range is a `400`
`invalid-value`. `count` is `null` where the total is unknown, such as when browsing messages; use
`hasNext` to find out whether another page follows. Filters (`q`, `sort` and the endpoint's own) sit beside
`page` and `size`.

## Errors

Every error is `application/problem+json` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)), including
`401`, `403` and errors raised by the framework. The `type` is a stable URI,
`https://artemis-studio.dev/problems/<slug>`, so switch on it rather than on the message.

| Type | Status | Meaning |
| --- | --- | --- |
| `unauthenticated` | 401 | No credentials, or a token that is not valid |
| `forbidden`, `access-denied` | 403 | Signed in, but not allowed |
| `csrf` | 403 | A browser session sent a change without its CSRF token |
| `bad-request`, `validation`, `invalid-value` | 400 | The request could not be read or a field is not valid; `validation` lists `errors` |
| `invalid-api-version` | 400 | The path names a version that does not exist |
| `not-found` | 404 | No such resource or route |
| `method-not-allowed`, `not-acceptable`, `unsupported-media-type` | 405, 406, 415 | The method, `Accept` or `Content-Type` is not served |
| `rate-limited`, `login-throttled`, `too-many-queries` | 429 | Too many requests; wait `Retry-After` seconds |
| `internal-error` | 500 | Unexpected; the body carries a `requestId` to quote, never the cause |

Modules add types of their own, for example `broker-unreachable`; the document lists what each operation
can return.

## Rate limits

Requests made with an API token are limited per token. Every such response carries
`RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset` (seconds until the window resets), and a
refused one is `429` with `Retry-After`. Browser sessions are not limited. Every `429` from the API carries
`Retry-After`, whichever limit produced it.

## Idempotency

Send an `Idempotency-Key` header (1 to 255 printable ASCII characters, unique per logical attempt) with any
`POST`, `PUT`, `PATCH` or `DELETE` you may need to retry.

- A repeat of the same key and the same request (method, path, query and body) within 24 hours returns the
  original status and body, with `Idempotent-Replayed: true`, and applies nothing again.
- The same key with a different request is `422` `idempotency-key-reused`.
- A repeat while the first is still running is `409` `idempotency-in-progress` with `Retry-After: 1`.
- A `5xx` response is not kept, so retrying it runs the request again.
- Keys belong to the calling user: tokens and sessions of one user share them, different users never do.
- Uploads (`multipart`) refuse a key with `400` `idempotency-unsupported`.

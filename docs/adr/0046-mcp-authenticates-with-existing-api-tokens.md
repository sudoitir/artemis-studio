# ADR-0046: MCP authenticates with the existing personal API tokens

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: Mahdi Amirabdollahi

## Context

The MCP surface (ADR-0045) needs a credential. The browser cannot be it: session
cookies exist because `EventSource` cannot set headers (ADR-0037), and an MCP
client is not a browser and has no session.

Three plausible credentials were available. Spring AI's starter offers an
`mcpServerApiKey()` helper — a single shared secret checked by the transport.
Spring Security offers a full OAuth2 resource server, which the MCP specification
itself describes for remote servers. And Studio already has personal API tokens
(ADR-0039): `as_`-prefixed, SHA-256 hashed, looked up by prefix, with grants
intersected against the owner's *live* grants on every request.

The deciding question is not "which is most standard" but "how many authorization
models does this product have". Studio's whole permission story is one scope-walk
(ADR-0038) over grants that belong to a user. Any credential that carries its own
permissions is a second model, and two models means two places to revoke, two
places to audit, and one of them eventually drifts.

## Decision

**We will authenticate `/mcp` with the ADR-0039 personal API tokens, and add
nothing else.** `ApiTokenAuthenticationFilter` already runs on every request;
`SecurityConfig` gains one line putting `/mcp` and `/mcp/**` behind
`.authenticated()`, and that is the entirety of the authentication work.

Consequences of that choice, stated so they are not rediscovered later:

- A key is **bounded by its owner's live grants**, not by what it was minted
  with. Narrowing a user's grants narrows every key they hold, immediately, with
  no key-side action.
- The audit row is the owner's, with the key's name attached — a purge through
  MCP reads `ada [token: laptop-agent]` in `/clusters/{id}/audit`, exactly as one
  through `curl` does (ADR-0041).
- `ClusterAccessGuard` and `@PreAuthorize` are the enforcement. The MCP layer
  adds no check of its own, which is the point: a check in the adapter would be a
  second copy of policy, free to drift from the REST one.
- CSRF does not apply. The filter chain already exempts requests carrying an
  `Authorization` header, because a bearer credential is not ambient and there is
  nothing for a cross-site request to ride on.

Keys are minted from `/account` → API keys, with an explicit permission and scope
picker. Before this change the UI minted keys with **no** grants at all — they
authenticated and could do nothing — which was survivable when a key was a
curiosity and is not when it is how an assistant connects.

## Consequences

- One credential store, one revocation path, one audit identity. A compromised
  key is revoked where every other key is revoked.
- A key cannot outlive or exceed its owner. That is the safety property, and it
  is also the limitation: there is no way to give an assistant a permission its
  human operator does not hold, and no service identity independent of a person.
  A shared "automation" user is the workaround, and it is a real one people will
  reach for.
- No token endpoint, no discovery document, no refresh. A generic MCP client that
  expects OAuth2 will not auto-configure against Studio; the user pastes a key
  into a header. That is a worse first-run experience than a standard flow, and
  it is the cost of not running an authorization server.
- Key rotation is manual. There is no expiry policy beyond the optional
  `expiresAt` a key is minted with.

## Alternatives considered

**Spring AI's `mcpServerApiKey()`.** One line of configuration. Rejected outright:
a single shared secret has no owner, so every audit row would be anonymous and
every key would carry the union of all permissions — the exact opposite of
ADR-0038.

**OAuth2 resource server.** What the MCP specification describes for remote
servers, and the right answer for a multi-tenant hosted product. Rejected for now
because it means running or integrating an authorization server for a
single-instance self-hosted tool, and because the tokens it issues would still
have to be mapped back onto Studio grants — the new machinery buys a standard
handshake, not a better authorization model. Worth revisiting if Studio is ever
hosted for tenants it does not own.

**A new MCP-specific credential type with its own permission set.** Rejected as
the second authorization model this ADR exists to avoid.

## Why

Studio shows and stores broker message content exactly as it found it. Four problems follow:

- A `VIEWER` holds `message:read`, so a read-only on-call user sees card numbers, emails and bearer tokens in full.
- Request-reply payloads are readable with only `cluster:read`.
- The message index keeps raw bodies in Postgres and indexes them for full-text search.
- SQL text and selector filters, which often carry literal customer values, are copied into the audit trail.

An installation under GDPR or PCI DSS cannot deploy Studio against production brokers until that changes. The roadmap's first item names it: "A · Data governance".

## What Changes

- **A content policy.** One Studio-wide policy decides, for every header, property and body value, whether it is shown clear, masked, or dropped.
  - **Rules** match a header, a property name, or a JSON body path, optionally narrowed by an address pattern.
  - **Data classes** carry the default action: credential, payment card number, IBAN, email, phone, national identifier, personal data.
- **Credentials are never shown and never stored.** Built-in rules ship enabled:
  - names: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, and property names containing `password`, `secret`, `token` or `api-key`;
  - values: any bearer token or JWT, whatever field it is in.

  Such a value is replaced by a marker for every user, including those allowed to see other sensitive data. A built-in rule can be disabled, not deleted, and disabling it is audited.
- **PII is detected automatically and masked immediately.**
  - Detectors run over property and body values: card number with a checksum, IBAN with a checksum, email, phone, bearer/JWT.
  - A value detected in a field no rule covers is masked straight away and lands in a classification inbox.
  - From the inbox, a governance administrator confirms it into a rule or dismisses it as a false positive. Dismissing is audited.
- **Content that cannot be inspected is withheld, and says why.** This covers a binary or unrecognised body, and the part of a body beyond the scan limit. Neither is shown or stored in clear to a user without clear access.
- **Role-based redaction.**
  - A new permission, `message:clear`, granted at any scope, shows sensitive values in clear.
  - Every response still marks which values are sensitive.
  - Serving clear content is audited per query: classes and counts, never the values.
  - Wildcard grants (`*`, `message:*`) include it.
- **Masked at rest, original sealed.** When Studio stores a message (the message index, capture, request-reply payloads), it stores the masked form. Full-text and SQL search therefore never see a sensitive value. Originals of non-credential values are kept encrypted beside the row and decrypted only for a caller holding `message:clear`. When the policy changes, rows already stored are re-masked in the background, with visible progress.
- **Every egress path is governed:** browse and message detail, the MCP `browse_messages` tool, SQL console results and tails, CSV/JSON export, request-reply flow detail, broker event properties, and audit parameters.
- **SQL cannot be used as an oracle.** A caller without `message:clear` is refused a predicate on a classified header or property, and the refusal names the field. Body predicates are evaluated over the masked body.
- **Governance screens:** a rules table, the classification inbox, and re-mask progress. Masked, withheld and clear-by-grant values are presented distinctly wherever message content appears.
- **BREAKING — permission change.**
  - Request-reply flow payloads now require `message:read`; `cluster:read` alone no longer shows them.
  - Browsing no longer returns credential headers and properties to anyone.
  - A user who needs clear PII needs `message:clear`. Grant it to a role, or keep using a role with `*`.
- **BREAKING — invalid-selector errors** no longer echo the selector text.

## Capabilities

### New Capabilities

- `data-governance`: the content policy covers:
  - data classes and actions;
  - rules and the built-in credential rules;
  - automatic classification and the inbox;
  - withheld content;
  - the clear-content permission and its audit;
  - masking at rest with sealed originals;
  - re-masking on policy change;
  - the governance screens.

### Modified Capabilities

- `message-operations`: browse and detail return governed content, not the broker's content verbatim; invalid-selector errors do not echo the selector.
- `message-index`: indexed payload is stored masked, with sealed originals and a policy version.
- `message-capture`: captured messages are stored masked.
- `sql-console`: governed rows and exports; the audited query text is masked; predicates on classified fields need clear access.
- `request-reply-tracing`: captured payloads are stored masked, and reading them requires `message:read`.
- `mcp-server`: message detail through MCP is governed exactly as through REST.
- `audit-log`: parameters are masked before they are written; clear reads and policy changes are audited.
- `authorization`: new permissions `message:clear`, `governance:read`, `governance:write`.
- `operator-ui`: masked, withheld and clear-by-grant values are presented distinctly and never as real values.

## Impact

- **Backend.**
  - New required platform module for governance: policy engine, detectors, rules and inbox persistence, sealing via the existing secret vault, re-mask job.
  - A new kernel audit extension point for masking parameters.
  - Changes in the messages, SQL, request-reply and events features at each point where content leaves or is stored.
- **Database.**
  - New governance tables.
  - New sealed-original and policy-version columns on `message_index` and `rr_event`, in new changesets.
- **API.** New governance endpoints. Message, SQL and request-reply DTOs gain redaction and withheld descriptors. Generated frontend types are regenerated.
- **Frontend.** New governance feature, and a shared redacted-value component used by message detail, the SQL grid and flow detail.
- **Performance.** Classification runs on every governed message, bounded by a scan limit (256 KiB by default). The capture drain path must keep pace; that is measured, not assumed.
- **Decisions.** ADR-0075. The parked change `03-message-replay-from-payload` inherits a constraint: replay uses unsealed originals, requires `message:clear`, and refuses a message with dropped credentials or withheld content.
- **Dependencies.** None added.

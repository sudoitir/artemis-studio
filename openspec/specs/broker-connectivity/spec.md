## Purpose

Defines how Artemis Studio reaches an Apache ActiveMQ Artemis broker over its
Jolokia HTTP management endpoint: what address it is given, how it batches
reads and operations into one request per node, how broker credentials are
stored, how TLS is configured, and how connection failures are reported.

## Requirements

### Requirement: Seed address is an explicit Jolokia base URL

The system SHALL accept the broker management address as a complete Jolokia
base URL and SHALL NOT derive, guess, or rewrite it. Studio SHALL support the
Artemis web console path (`/console/jolokia`) and a standalone agent path
(`/jolokia`) purely as caller-supplied values.

#### Scenario: Console-style URL accepted

- **WHEN** a user supplies `http://broker-1:8161/console/jolokia`
- **THEN** Studio issues management requests against exactly that base URL

#### Scenario: Standalone-agent URL accepted

- **WHEN** a user supplies `https://broker-1:8778/jolokia`
- **THEN** Studio issues management requests against exactly that base URL

#### Scenario: Bare host is rejected

- **WHEN** a user supplies `broker-1:8161` with no scheme or path
- **THEN** registration fails with a validation error naming the expected form

### Requirement: One batched request per node

For any scrape or probe that needs more than one attribute or operation from a
single node, the system SHALL send exactly one Jolokia bulk POST containing a
JSON array of request objects, mixing `read` and `exec` entries, rather than
one HTTP call per attribute, operation, or queue.

#### Scenario: HA attributes and topology in one call

- **WHEN** a refresh needs `Active`, `Started`, `Backup`, `ReplicaSync`,
  `NodeID`, `Clustered`, `Version` and `listNetworkTopology()` from a node
- **THEN** Studio sends a single POST whose body is a JSON array of those requests

#### Scenario: A configuration read is one call per node

- **WHEN** a node's configuration is read for comparison against another node —
  broker attributes, address settings, security settings and acceptors
- **THEN** Studio sends a single POST to that node containing all of those requests

#### Scenario: Per-entry errors are isolated

- **WHEN** a batched response array contains one entry with `status` 404 and an
  `error` / `error_type` field, and other entries with `status` 200
- **THEN** Studio consumes the successful entries and records the failed entry
  without discarding the batch

#### Scenario: Whole-call HTTP 200 with failed entries

- **WHEN** the batched POST returns HTTP 200 but one array entry has a non-200
  `status`
- **THEN** Studio inspects each entry's own `status` and does not treat the
  call as wholly successful

### Requirement: Double-encoded operation results are parsed

The system SHALL treat the `value` returned by `listNetworkTopology()` and
`listQueues(...)` as a JSON-encoded string and parse it a second time to obtain
the structured result.

#### Scenario: Topology value is a JSON string

- **WHEN** an `exec` of `listNetworkTopology()` returns `value` as a quoted
  JSON string
- **THEN** Studio parses that string into an array of node objects before use

### Requirement: Broker credentials are encrypted at rest

The system SHALL store broker credentials only as authenticated ciphertext.
Encryption SHALL use AES in GCM mode with a 96-bit random nonce per secret and
a 128-bit authentication tag. The root key SHALL be read from the
`ARTEMIS_STUDIO_SECRET_KEY` environment value, which MUST decode as base64 to
exactly 32 bytes; if it is missing or the wrong length the application SHALL
fail to start. The additional authenticated data SHALL bind each ciphertext to
its owning cluster and credential kind so a ciphertext cannot be moved to
another row.

#### Scenario: Missing key stops startup

- **WHEN** the application starts with no `ARTEMIS_STUDIO_SECRET_KEY` set
- **THEN** startup fails with an error that names the missing key

#### Scenario: Wrong-length key stops startup

- **WHEN** `ARTEMIS_STUDIO_SECRET_KEY` decodes to 20 bytes
- **THEN** startup fails with an error stating a 32-byte key is required

#### Scenario: Credentials never leave in plaintext responses

- **WHEN** any cluster or node is returned from the API
- **THEN** the response body contains no broker password or secret field

#### Scenario: Ciphertext is bound to its row

- **WHEN** a stored ciphertext for cluster A is decrypted with cluster B's
  identity as additional authenticated data
- **THEN** decryption fails rather than returning a value

### Requirement: TLS to brokers is configured by named bundle

When a broker requires TLS, the system SHALL obtain trust material and client
identity from a named Spring Boot SSL bundle referenced by the cluster's TLS
configuration, and SHALL apply that bundle's hostname-verification setting.
Studio SHALL NOT require a filesystem path plus password to be entered for a broker.

#### Scenario: HTTPS broker with a configured bundle

- **WHEN** a cluster's TLS reference names an SSL bundle that exists
- **THEN** Studio's requests to that broker use the bundle's trust and key material

#### Scenario: Missing bundle is reported

- **WHEN** a cluster's TLS reference names a bundle that is not configured
- **THEN** the connection attempt fails with an error naming the missing bundle

### Requirement: Connection failures map to a stable taxonomy

Every failed broker connection SHALL be reported as one of a fixed set of
classified errors, each carrying a stable machine-readable type and a
human-readable explanation. The set SHALL include: unreachable host, rejected
credentials, reachable Jolokia agent with no Artemis broker registered, wrong
path (no agent), and TLS handshake failure.

#### Scenario: Nothing listening

- **WHEN** the seed host refuses the connection or does not resolve
- **THEN** the error is classified `UNREACHABLE`

#### Scenario: Bad credentials

- **WHEN** the broker responds 401 or 403
- **THEN** the error is classified `UNAUTHORIZED`

#### Scenario: Jolokia present, not Artemis

- **WHEN** the agent responds 200 but no `org.apache.activemq.artemis:broker=*`
  MBean is found
- **THEN** the error is classified `NOT_ARTEMIS`

#### Scenario: No agent at the path

- **WHEN** the base URL returns 404
- **THEN** the error is classified `WRONG_PATH` and the message suggests
  `/console/jolokia`

#### Scenario: TLS cannot be negotiated

- **WHEN** the TLS handshake to an HTTPS broker fails
- **THEN** the error is classified `TLS_FAILED` and names the bundle and whether
  hostname verification is enabled

### Requirement: Broker MBean name is resolved, not assumed

The system SHALL discover the broker's management MBean object name by issuing
a Jolokia `search` for `org.apache.activemq.artemis:broker=*` rather than
assuming the broker's configured `<name>`.

#### Scenario: Broker name unknown at registration

- **WHEN** a cluster is registered and the broker's `<name>` is not supplied
- **THEN** Studio issues a `search` request and uses the returned object name
  for subsequent reads

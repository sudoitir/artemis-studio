## MODIFIED Requirements

### Requirement: Broker credentials are encrypted at rest

The system SHALL store broker credentials only as envelope-encrypted sealed values (`secret-management`): the credential encrypted with AES-GCM under its own data key, and the data key wrapped by a versioned key-encryption key from the configured key provider. With no provider configured, the key comes from `ARTEMIS_STUDIO_SECRET_KEY`, which MUST decode as base64 to exactly 32 bytes (or list numbered keys that each do); if no valid key is available the application SHALL fail to start. The additional authenticated data SHALL bind each ciphertext to its owning cluster and credential kind so a sealed value cannot be moved to another row.

#### Scenario: Missing key stops startup

- **WHEN** the application starts with the environment provider and no `ARTEMIS_STUDIO_SECRET_KEY` set
- **THEN** startup fails with an error that names the missing key

#### Scenario: Wrong-length key stops startup

- **WHEN** `ARTEMIS_STUDIO_SECRET_KEY` decodes to 20 bytes
- **THEN** startup fails with an error stating a 32-byte key is required

#### Scenario: Credentials never leave in plaintext responses

- **WHEN** any cluster or node is returned from the API
- **THEN** the response body contains no broker password or secret field

#### Scenario: Ciphertext is bound to its row

- **WHEN** a sealed value for cluster A is opened with cluster B's identity as additional authenticated data
- **THEN** decryption fails rather than returning a value

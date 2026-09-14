## Purpose

Controls what broker message content Studio shows and stores. A Studio-wide content policy masks sensitive headers, properties and body values on every path out of Studio and in Studio's own database. Personal data it detects is masked automatically, and only a holder of the clear-content permission sees originals, with every clear view audited.

## ADDED Requirements

### Requirement: Sensitive values are classified into fixed data classes with a default action

The system SHALL classify sensitive message values into a fixed set of data classes. Each class SHALL have a default action:

| Class | Default action |
| --- | --- |
| credential | drop |
| payment card number | partial (last four digits kept) |
| IBAN | partial (last four characters kept) |
| email | redact |
| phone | redact |
| national identifier | redact |
| personal data | redact |

- **Drop**: the value SHALL be replaced by a marker and SHALL NOT be shown or stored in any form.
- **Partial**: only the stated suffix SHALL remain visible.
- **Redact**: the whole value SHALL be replaced by a marker naming its class.

A masked value SHALL never be presented as if it were the real value.

#### Scenario: A card number is partially masked

- **WHEN** a message property holding a payment card number is shown to a user without clear access
- **THEN** only its last four digits are visible and the value is marked as a masked payment card number

#### Scenario: A credential is dropped

- **WHEN** a message carries a credential value
- **THEN** every response shows a marker naming the credential class in its place, and no response or stored row contains the value

### Requirement: Masking rules match headers, properties and JSON body paths, optionally by address

The system SHALL maintain one Studio-wide set of masking rules. A rule SHALL name:

- a target: a message header, an application property name pattern, or a JSON body path;
- a data class;
- optionally, an action that overrides the class default;
- optionally, an address pattern that limits it to matching addresses.

A rule SHALL be enabled or disabled. Creating, changing, enabling, disabling or deleting a rule SHALL require the governance write permission and SHALL be audited. Reading rules SHALL require the governance read permission.

#### Scenario: An address-scoped rule applies only to its addresses

- **WHEN** a rule masks property `customerEmail` for addresses matching `orders.#`
- **THEN** that property is masked on messages from `orders.eu` and shown unchanged on messages from `billing.eu` unless another rule or detector applies

#### Scenario: A rule change requires governance write

- **WHEN** a user without the governance write permission attempts to create a rule
- **THEN** the request is rejected and no rule is created

### Requirement: Credential rules are built in and enabled by default

The system SHALL ship enabled, built-in rules that classify the following as credentials:

- the headers or properties named `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie`, matched case-insensitively;
- any property whose name contains `password`, `secret`, `token`, `api-key` or `api_key`;
- any value that is a bearer token or has the shape of a JSON Web Token, wherever it appears.

A built-in rule SHALL NOT be deletable. It MAY be disabled; disabling it SHALL be audited.

#### Scenario: An Authorization header is never shown

- **WHEN** a message with an `Authorization` property is browsed by any user, including one with clear access
- **THEN** the value is shown as a dropped credential

#### Scenario: A bearer token in an unnamed field is dropped

- **WHEN** a JSON body field `note` contains `Bearer eyJhbGciOi...`
- **THEN** the field's value is dropped as a credential even though no rule names that field

#### Scenario: A built-in rule cannot be deleted

- **WHEN** a governance administrator attempts to delete a built-in credential rule
- **THEN** the request is rejected, and the response states that built-in rules can only be disabled

### Requirement: Personal data is detected automatically and masked before review

The system SHALL scan every property value and every body value it can inspect for:

- payment card numbers that pass the Luhn checksum;
- IBANs that pass the mod-97 checksum;
- email addresses;
- phone numbers;
- bearer tokens and JSON Web Tokens.

A detected value SHALL be masked by its class's default action immediately, whether or not a rule names its field.

Each detection in a field that no rule covers SHALL be recorded as a finding with:

- the address;
- the field location;
- the class;
- first-seen and last-seen times;
- a hit count.

The finding itself SHALL NOT record the value. Recording findings SHALL NOT write to the database once per message.

#### Scenario: An undeclared card number is masked at once

- **WHEN** a message's JSON body contains a valid card number in field `payment.ref` and no rule names that field
- **THEN** the value is masked, and a finding for `payment.ref` classed as a payment card number appears in the inbox

#### Scenario: A digit string that fails the checksum is not a card number

- **WHEN** a property holds a 16-digit string that fails the Luhn checksum
- **THEN** it is not classified as a payment card number

### Requirement: Findings are confirmed or dismissed by a governance administrator

The inbox SHALL list open findings, with their class, address, field location, hit count and times.

A holder of the governance write permission SHALL be able to:

- **confirm** a finding, which creates a rule for that field and class;
- **dismiss** it as a false positive, which creates an exception so the detector no longer masks that class in that field for that address.

Both actions SHALL be audited with the finding's identity and SHALL NOT include any detected value.

#### Scenario: Confirming a finding creates a rule

- **WHEN** an administrator confirms a finding for field `payment.ref` on `orders.#`
- **THEN** a rule for that field and class exists, and the finding is closed

#### Scenario: Dismissing a finding stops masking and is audited

- **WHEN** an administrator dismisses a phone-number finding for field `orderNumber`
- **THEN** values in that field are no longer masked as phone numbers for that address, and an audit record names the dismissal

### Requirement: Content that cannot be inspected is withheld with its reason

For a user without clear access, the system SHALL withhold:

- a message body it cannot inspect, meaning a binary body or a body of an unrecognised format;
- the portion of a body beyond the configured scan limit.

A withheld body or portion SHALL be neither shown nor stored in clear. The response SHALL state what was withheld and why. Where the reason is the scan limit, the response SHALL name the setting that changes it. Header and property rules and detectors SHALL still apply to such a message.

#### Scenario: A binary body is withheld

- **WHEN** a user without clear access opens a message with a binary body
- **THEN** the body is not shown, and the response states that a binary body cannot be classified and was withheld

#### Scenario: Bytes past the scan limit are withheld

- **WHEN** a text body is larger than the scan limit
- **THEN** only the scanned portion is shown (masked where needed), and the response states that the remainder was not scanned and names the scan-limit setting

### Requirement: The clear-content permission shows sensitive values and is audited

A caller holding the `message:clear` permission at a cluster's scope SHALL receive that cluster's sensitive values in clear, except credentials, which SHALL remain dropped. Withheld content SHALL be shown to such a caller. A response served in clear SHALL still identify every sensitive value and its class.

When a response contains at least one sensitive value served in clear, the system SHALL write an audit record. It SHALL name the caller, cluster and target, and the classes and counts served. It SHALL NOT include the values.

#### Scenario: A clear-access user sees an email in clear, marked as sensitive

- **WHEN** a user with `message:clear` on the cluster opens a message whose property holds an email
- **THEN** the email is shown in clear and marked as sensitive, and an audit record of the clear view is written

#### Scenario: Clear access is scoped

- **WHEN** a user holds `message:clear` on cluster A only and opens a message on cluster B
- **THEN** sensitive values on cluster B are masked

### Requirement: Stored message content is masked, with originals sealed

Wherever Studio persists message content, the stored columns SHALL hold the governed, masked form. This covers the message index, captured messages and request-reply payloads.

Sealed originals:

- Originals of sensitive values other than credentials SHALL be stored encrypted and bound to their row.
- They SHALL be decrypted only to answer a caller holding `message:clear` for the row's cluster.
- Credentials SHALL NOT be sealed.

Every stored row SHALL record the version of the policy it was masked under.

#### Scenario: Full-text search cannot find a masked value

- **WHEN** an indexed message's body contained an email address and an operator searches the index for that address
- **THEN** no row matches

#### Scenario: A clear-access user reads a stored original

- **WHEN** a user with `message:clear` queries the index for a row whose email was masked when stored
- **THEN** the row shows the original email, marked as sensitive, and the clear view is audited

### Requirement: A policy change is enforced on stored content

When the policy changes, the system SHALL immediately apply the new policy to stored content on its way out. It SHALL then re-mask rows stored under an earlier policy version in bounded background batches, until none remain within retention.

The governance screen SHALL show how many stored rows are still held under an earlier version.

#### Scenario: A new rule protects stored rows immediately

- **WHEN** a rule masking property `ssn` is created, and a row indexed earlier holds `ssn` in clear
- **THEN** a query returning that row shows `ssn` masked

#### Scenario: Re-masking converges and reports progress

- **WHEN** the policy changes while the index holds rows under the earlier version
- **THEN** the governance screen reports a decreasing count of rows under earlier versions until it reaches zero

### Requirement: The scan limit is a studio setting

The number of body bytes scanned per message SHALL be a studio setting with a default of 256 KiB. Changing it SHALL follow the studio settings rules. It SHALL apply to messages governed after the change.

#### Scenario: Raising the scan limit reveals more of a large body

- **WHEN** an administrator raises the scan limit above a message body's size
- **THEN** the next read of that message shows the whole body, masked where needed, with nothing withheld for size

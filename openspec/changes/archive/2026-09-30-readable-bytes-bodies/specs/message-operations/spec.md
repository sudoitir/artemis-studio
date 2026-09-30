## ADDED Requirements

### Requirement: Text carried in a bytes message is read as text

When the system reads a bytes message from the broker, it SHALL present the body as text wherever the
body's bytes are well-formed UTF-8 containing no control characters other than tab, line feed and
carriage return. Where the bytes begin with a gzip or zlib (deflate) header and decompress, within a
fixed size ceiling, to such text, the system SHALL present the decompressed text and SHALL report the
compression alongside the detected format. A body that fails any of these tests SHALL remain binary
and SHALL be presented as it was before this requirement. The decision SHALL be made once, where the
body is read, so that every view of the message (the message view, the queue's body column, the SQL
console, the message index, request-reply payloads and tool access) presents the same body the same way.

The interface SHALL NOT state that the body was converted. The message's type SHALL still be reported
as a bytes message. Relaying a message to another destination SHALL carry its original bytes unchanged.
Reading the body SHALL never fail because a decode failed: a body that cannot be decoded is binary.

#### Scenario: JSON sent as bytes reads as JSON

- **WHEN** a producer sends a bytes message whose body is the UTF-8 encoding of a JSON object, and an
  operator opens it from the queue's messages
- **THEN** the body is shown indented as JSON, the format reads JSON, the message type reads bytes, and
  no notice about decoding is shown

#### Scenario: The queue's body column shows the text

- **WHEN** the queue's messages include a bytes message carrying UTF-8 text
- **THEN** the body column previews that text, not an encoding of its bytes

#### Scenario: Compressed JSON is decompressed and labelled

- **WHEN** a bytes message's body is gzip-compressed JSON below the decompression ceiling
- **THEN** the body is shown as JSON and its format names gzip

#### Scenario: A decompression bomb stays binary

- **WHEN** a gzip body would decompress to more than the decompression ceiling
- **THEN** decompression stops at the ceiling, the body is reported as binary gzip with its hexadecimal
  dump, and the read completes normally

#### Scenario: Binary stays binary

- **WHEN** a bytes message's body is not well-formed UTF-8, or contains a NUL or another control
  character
- **THEN** the body is reported as binary and shown as a hexadecimal dump

#### Scenario: A relayed message keeps its bytes

- **WHEN** a bytes message carrying UTF-8 JSON is moved or copied to another queue
- **THEN** the destination message carries the original bytes as a bytes message

## MODIFIED Requirements

### Requirement: A formatted body is presented readably, with raw always available

A body in a structured format SHALL be presentable indented and syntax-highlighted, with
a control to switch between the formatted and the raw body, and controls to copy and to
download the body. A JSON body that parsed and is within the indentation ceiling SHALL also be
presentable as a tree: objects and arrays collapsible, the tree searchable by key and value, every
node reachable by keyboard, and each node offering to copy its path in the form the SQL console
accepts for a JSON body path. Keys and values SHALL be rendered as text, never interpreted as markup.
A binary body SHALL be presented as a hexadecimal and ASCII dump of
its leading bytes; a binary body SHALL NOT be decoded as text into the body view.

#### Scenario: Formatted and raw

- **WHEN** an operator views a JSON body
- **THEN** it is shown indented and highlighted, and can be switched to the raw body

#### Scenario: Tree view

- **WHEN** an operator switches a JSON body to the tree presentation and collapses an object
- **THEN** the object's members are hidden, the object states how many it holds, and expanding it
  shows them again

#### Scenario: Copy a field's path

- **WHEN** an operator copies the path of a nested field from the tree
- **THEN** the clipboard holds that field's path in the SQL console's JSON body path form

#### Scenario: Binary is dumped, not decoded

- **WHEN** an operator views a body whose format is binary
- **THEN** a hexadecimal and ASCII dump of its leading bytes is shown rather than a text
  rendering of those bytes

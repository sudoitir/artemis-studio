## ADDED Requirements

### Requirement: A message body's format is detected and never overclaimed

The system SHALL report a detected format for a browsed message's body, and SHALL NOT
report a format it did not successfully parse. Detection SHALL prefer a format declared
by the producer — a content type carried on the message or in its application
properties — over any inspection of the body's bytes. Where no format is declared, the
system MAY infer a structured format from the body, and SHALL do so only by parsing it:
a body reported as JSON SHALL have parsed as JSON, and a body reported as XML SHALL have
parsed as XML with no parser error. A body that matches nothing SHALL be reported as
plain text.

Binary bodies SHALL be identified by their leading bytes where they match a known
container — at minimum gzip, zip, Java serialization, and Avro — and reported by that
name.

#### Scenario: A declared content type wins

- **WHEN** a message declares a content type that disagrees with what its body looks like
- **THEN** the declared type is the reported format

#### Scenario: Inference requires a successful parse

- **WHEN** a text body begins with `{` but does not parse as JSON
- **THEN** the reported format is text, not JSON

#### Scenario: XML parser errors are not ignored

- **WHEN** a text body is offered to the XML parser and the parser reports an error
- **THEN** the body is not reported as XML

#### Scenario: A known binary container is named

- **WHEN** a binary body begins with the gzip magic bytes
- **THEN** the reported format names gzip

### Requirement: A truncated body reports truncation, not a format failure

When a message body is known to have been truncated by the broker's management attribute
size limit, and the truncated body therefore fails to parse, the system SHALL report that
formatting is unavailable **because the broker truncated the body**, and SHALL point at
the existing truncation notice and its enabling `broker.xml` snippet. It SHALL NOT report
the body as malformed or as not being the format it claims.

#### Scenario: Truncated JSON

- **WHEN** a message declared as JSON is truncated and its remaining text does not parse
- **THEN** the panel says the body was truncated by the broker and cannot be formatted,
  and does not say the body is not JSON

### Requirement: A formatted body is presented readably, with raw always available

A body in a structured format SHALL be presentable indented and syntax-highlighted, with
a control to switch between the formatted and the raw body, and controls to copy and to
download the body. A binary body SHALL be presented as a hexadecimal and ASCII dump of
its leading bytes; a binary body SHALL NOT be decoded as text into the body view.

#### Scenario: Formatted and raw

- **WHEN** an operator views a JSON body
- **THEN** it is shown indented and highlighted, and can be switched to the raw body

#### Scenario: Binary is dumped, not decoded

- **WHEN** an operator views a body whose format is binary
- **THEN** a hexadecimal and ASCII dump of its leading bytes is shown rather than a text
  rendering of those bytes

### Requirement: Oversized bodies degrade instead of blocking the interface

The system SHALL apply size ceilings above which detection inspects only a leading
portion of the body, above which indentation is not performed, and above which syntax
highlighting is not performed. Above every ceiling the body SHALL still be shown as
plain text with copy and download available, and the interface SHALL state that
formatting is off because of the body's size.

#### Scenario: A very large body is still usable

- **WHEN** an operator views a body larger than every formatting ceiling
- **THEN** the body is shown unformatted, the panel states that formatting is off for a
  body this size, and copy and download remain available

### Requirement: A message's type is reported by name

A browsed message's type SHALL be presented by its name rather than as the broker's
integer code.

#### Scenario: Type is a word

- **WHEN** a browsed message carries the broker's type code for a text message
- **THEN** the detail view shows the type's name, not the integer

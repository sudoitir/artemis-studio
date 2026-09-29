## ADDED Requirements

### Requirement: A schema is inferred per address from samples
The system SHALL infer a structure for an address from sampled payloads in JSON, XML, Avro and Protobuf, and SHALL state the format, the number of samples, the period and its confidence.

#### Scenario: JSON inference
- **WHEN** samples of an address are JSON objects
- **THEN** the schema lists fields, types and whether each is always present

#### Scenario: Too few samples
- **WHEN** too few samples exist
- **THEN** the schema is marked low confidence or not inferred

#### Scenario: Unknown format
- **WHEN** payloads are none of the supported formats
- **THEN** the address is listed as unrecognized

### Requirement: Formats that need a definition say so
Where a format cannot be decoded without a definition, such as Protobuf without a descriptor, the system SHALL say what is missing and SHALL NOT invent field names.

#### Scenario: Protobuf without descriptor
- **WHEN** samples are Protobuf and no descriptor is provided
- **THEN** the catalog shows structure by field number only, or asks for the descriptor

### Requirement: A catalog lists payload structures
The interface SHALL show a catalog of addresses with their format, schema and history of versions, searchable by field name.

#### Scenario: Search by field
- **WHEN** a user searches for a field name
- **THEN** addresses whose schema contains it are listed

### Requirement: Schema drift is detected and can alert
The system SHALL detect when an address's recent payloads no longer match its schema, describe the change, and make drift an alertable condition.

#### Scenario: A field disappears
- **WHEN** recent payloads omit a formerly required field
- **THEN** drift is reported and a bound rule fires

#### Scenario: A new version
- **WHEN** a stable new structure appears
- **THEN** it is recorded as a new version of the schema

### Requirement: Inference respects data governance
Inference, catalog display and drift reports SHALL follow masking and PII rules, showing no sample values that governance masks, and SHALL only read addresses the user may read.

#### Scenario: A masked field
- **WHEN** a field is masked by policy
- **THEN** the catalog shows the field and type but no example values

#### Scenario: No permission
- **WHEN** a user cannot read an address
- **THEN** its schema is not shown to them

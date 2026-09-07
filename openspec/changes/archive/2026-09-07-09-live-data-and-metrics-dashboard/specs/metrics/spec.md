## MODIFIED Requirements

### Requirement: Charts disclose sampling gaps and coverage limits

The system SHALL render a gap in the underlying samples as a gap in the chart, not as an
interpolated line, and SHALL disclose when a shown metric has no persisted history (for
example, request-reply latency, which is a live window only).

A chart's horizontal position SHALL be a function of a sample's timestamp, not of its
position in the returned sequence, so that a stretch of time for which no sample exists
occupies proportional empty space rather than collapsing to nothing. A bucket that is
absent from the response SHALL therefore be as visible as a bucket whose value is
absent.

Tick density and tick format SHALL follow the range being displayed rather than being
fixed, so that a fifteen-minute window and a seven-day window are each legible.

#### Scenario: A cold subject shows a gap, not a flat line

- **WHEN** a subject was not sampled during part of the requested range
- **THEN** the chart shows a visible gap for that part of the range rather than connecting
  across it

#### Scenario: An omitted bucket is a gap, not a shorter interval

- **WHEN** the response omits buckets entirely for a stretch of the requested range
- **THEN** the points either side of that stretch are separated in proportion to the time
  between them, rather than drawn adjacent

#### Scenario: Latency is labelled as a live window

- **WHEN** the request-reply latency chart is shown
- **THEN** it is captioned as reflecting only the current live window, not persisted
  history

#### Scenario: Tick format follows the range

- **WHEN** an operator switches between the narrowest and widest ranges
- **THEN** the axis labels change granularity with the range rather than repeating a
  single format

## ADDED Requirements

### Requirement: A relative metric window advances as time passes

The system SHALL advance the window of a metric view expressed as a range relative to
now, so that the most recent end of the chart continues to be the present for as long as
the view is open. The window SHALL advance in steps of the bucket width in use, not
continuously.

A window expressed as an absolute range SHALL NOT advance, so that a shared or
bookmarked link resolves to the same window every time it is opened.

#### Scenario: A live window keeps up

- **WHEN** an operator leaves a relative-range metric view open across more than one
  bucket interval
- **THEN** the window's end has advanced, and the chart shows buckets that did not exist
  when the view was opened

#### Scenario: An absolute window is stable

- **WHEN** an operator opens a metric view from a link carrying an absolute range
- **THEN** the window does not advance for as long as the view is open

### Requirement: A metric view distinguishes failure from absence

The system SHALL present a metric read that failed differently from a metric read that
succeeded with no samples, and SHALL state the cause of a failure. An absence of samples
SHALL NOT be rendered in a way that could be read as a report that the metric was zero
or quiet.

While a metric read is in progress and no previous data is held, the view SHALL occupy
the same vertical space it will occupy once resolved, so that resolving does not move the
page.

#### Scenario: A failed read says so

- **WHEN** a metric read fails
- **THEN** the view reports the failure and its cause, rather than reporting that there
  are no samples

#### Scenario: An empty window teaches

- **WHEN** a metric read succeeds and contains no samples for the requested window
- **THEN** the view says that the window contains no samples and why that may be, rather
  than rendering an empty plot

#### Scenario: Resolving does not move the page

- **WHEN** a metric read resolves, whether to data, to emptiness or to a failure
- **THEN** the vertical space the view occupies is unchanged

### Requirement: A metric view leads with the current values

The system SHALL present, above the charts, the current value of each metric the view
covers, together with how it moved across the displayed window. Values SHALL be rendered
with tabular figures and SHALL carry their unit.

The data behind the displayed window SHALL additionally be available as a table, so that
the view is usable without reading a rendered plot.

#### Scenario: The headline values are readable without a chart

- **WHEN** an operator opens a metric view
- **THEN** the current depth, ingress rate, egress rate and consumer count are stated as
  values, each with its unit

#### Scenario: A value with no current sample is stated, not blank

- **WHEN** a metric has no sample recent enough to state a current value
- **THEN** the view says so rather than rendering an empty or zero value

#### Scenario: The window is available as a table

- **WHEN** an operator chooses the tabular presentation of a metric view
- **THEN** the buckets of the displayed window are presented as rows with their
  timestamps and values

### Requirement: A metric view can be scoped to a single queue from its address

The system SHALL allow a metric view to be scoped to one queue, and SHALL carry that
scope in the view's address so that it can be shared and restored. A scoped view SHALL
state which queue it is scoped to and offer a way back to the cluster-wide view.

#### Scenario: A scoped view is shareable

- **WHEN** an operator scopes a metric view to one queue and shares the address
- **THEN** opening that address presents the same queue's series

#### Scenario: The scope is visible

- **WHEN** a metric view is scoped to one queue
- **THEN** the view states the queue it is scoped to, and offers a way back to the
  cluster-wide series

## ADDED Requirements

### Requirement: Content that moves on its own can be paused and honours reduced motion

A view that animates without operator input for longer than five seconds SHALL offer a
visible, keyboard-reachable control that pauses the animation, SHALL stop the animation
while the operator's system requests reduced motion, and SHALL stop it while the view is not
visible. Whatever the animation conveys SHALL also be conveyed without motion, so pausing
removes no information. Pausing an animation SHALL NOT pause data refresh; pausing data
refresh remains its own control.

#### Scenario: Pause keeps information

- **WHEN** an operator pauses an animated view
- **THEN** the movement stops and every value it conveyed is still shown as text or shape

#### Scenario: Reduced motion is honoured

- **WHEN** the operator's system requests reduced motion
- **THEN** the view opens without movement and offers no way for it to start on its own

#### Scenario: Hidden view stops moving

- **WHEN** the browser tab showing an animated view is hidden
- **THEN** the animation stops and resumes, unless paused, when the tab is shown again

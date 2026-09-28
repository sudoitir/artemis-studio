## ADDED Requirements

### Requirement: The operator chooses the colour scheme from the header

The header SHALL offer, on every authenticated screen, one control that switches between the light and dark colour schemes. The command palette SHALL offer the same action. The control's accessible name SHALL name the scheme it switches to. The choice SHALL be remembered in that browser across reloads, and a first visit SHALL use the dark scheme.

#### Scenario: Switching the scheme

- **WHEN** an operator activates the control while the dark scheme is showing
- **THEN** the console switches to the light scheme, and the control now offers to switch to dark

#### Scenario: The choice survives a reload

- **WHEN** an operator chooses the light scheme and reloads
- **THEN** the console opens in the light scheme

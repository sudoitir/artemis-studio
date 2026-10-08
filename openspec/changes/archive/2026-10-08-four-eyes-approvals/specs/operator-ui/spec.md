## MODIFIED Requirements

### Requirement: Settings is presented as grouped tabs

The Settings page SHALL present each settings section as a tab in a vertical tab list. The tabs SHALL be grouped under fixed headings in this order: the operator's own preferences, Studio-wide configuration, this cluster, and plugins. Studio-wide configuration SHALL have one tab per settings category. Each category SHALL be edited as a form whose inputs fit each setting's kind. A modified setting SHALL show its default and offer a reset.

One search SHALL find settings across all categories, and a filter SHALL show only modified settings. The open tab and the search SHALL be held in the address, so they can be shared and restored.

Edits SHALL form one draft across categories. A marker SHALL show on each category with unsaved changes. Leaving with unsaved changes SHALL ask first. The draft SHALL be applied in one step when no approval is needed. When approval is needed, the draft SHALL be reviewed first, as a list of current and new values, with a reason.

A change waiting for approval SHALL show beside its setting, with its requester, its age and a way to cancel it.

The tab list SHALL be operable from the keyboard, and changing tab SHALL move focus to the opened section's heading. A section contributed by a plugin SHALL appear under the plugins heading.

#### Scenario: The open tab survives a reload

- **WHEN** an operator opens the Broker credentials tab and reloads the page
- **THEN** the Broker credentials tab is open

#### Scenario: The tabs are keyboard operable

- **WHEN** focus is on the tab list and the operator presses the down arrow and Enter
- **THEN** the next tab opens and focus moves to its heading

#### Scenario: Search across categories

- **WHEN** an operator searches for "retention"
- **THEN** only categories with a matching setting are listed, with their match counts

#### Scenario: Change needs approval

- **WHEN** a team member edits two settings and a provider would hold the change
- **THEN** the primary action reads "Request approval", and submitting shows both changes as pending beside their settings

#### Scenario: Leaving with unsaved edits

- **WHEN** an operator with unsaved edits navigates away
- **THEN** Studio asks before discarding them

## ADDED Requirements

### Requirement: Administration SHALL use grouped navigation

The Administration page SHALL list its sections in a vertical navigation grouped under Access, Installation, Governance and Support. A plugin's section SHALL declare its group. The open section SHALL be held in the address.

#### Scenario: Plugin section

- **WHEN** a plugin contributes an administration section in the Governance group
- **THEN** it is listed under Governance

### Requirement: Held operations SHALL be visible and decidable in the UI

Studio SHALL show the outcome when an operation is held, not as a failure. The notice SHALL link to the request. Each request SHALL have a page showing:

- what will happen: changed values and the expected effect;
- the scope;
- the requester and how they signed in;
- the reason, the policy and the time left;
- a timeline of what happened to it.

The requester SHALL be able to cancel the request from that page. A provider SHALL be able to add its decision controls there. The Account page SHALL list the user's own requests. While break-glass is on, a banner SHALL say so on every page.

#### Scenario: Held purge in the UI

- **WHEN** a purge is held
- **THEN** the operator sees "Sent for approval" with a link to the request, not an error

#### Scenario: Requester cancels

- **WHEN** the requester cancels from the request page
- **THEN** the request shows as cancelled, and the approvers' notices are resolved

# notification-inbox Specification

## Purpose
Give every user an in-app inbox of notices, from Studio and from plugins, with a live unread count.

## Requirements

### Requirement: Every user SHALL have an in-app inbox

Studio SHALL keep, for each user, an inbox of notices sent by Studio features and plugins. The header SHALL show an unread count that updates live, without a page reload, on every Studio replica. Users SHALL be able to open the inbox, follow a notice's link, mark notices read, and dismiss them. A user SHALL only ever see their own notices.

#### Scenario: Live unread count

- **WHEN** a notice is posted to a user while they are on any page
- **THEN** the bell's unread count increases without a reload

#### Scenario: Another replica

- **WHEN** a notice is posted on one Studio replica while the user's browser is connected to another
- **THEN** the user still sees it live

#### Scenario: Reconnect

- **WHEN** the user's live connection drops and comes back
- **THEN** the inbox shows every notice posted meanwhile

#### Scenario: Someone else's notice

- **WHEN** a user requests another user's notice by id
- **THEN** it is not found

### Requirement: Notice links SHALL stay inside Studio

A notice's link SHALL be a path within Studio. Studio SHALL refuse a notice whose link points elsewhere.

#### Scenario: External link

- **WHEN** a plugin posts a notice linking to an external site
- **THEN** the notice is refused

### Requirement: Plugins SHALL post notices through a public API

A plugin SHALL be able to post notices to given users, or to every user who holds a permission at a scope. The notice's source SHALL be the plugin, and the plugin SHALL NOT be able to change it. Studio SHALL limit notice sizes and fan-out, and SHALL de-duplicate notices that share a key.

#### Scenario: Post to permission holders

- **WHEN** a plugin posts a notice to holders of a permission on a cluster
- **THEN** each enabled user holding it receives one notice with the plugin as its source

### Requirement: Notices SHALL expire

Studio SHALL delete notices past their retention, and read notices sooner, on a schedule an administrator can change.

#### Scenario: Retention

- **WHEN** a read notice is older than the read retention
- **THEN** it is deleted

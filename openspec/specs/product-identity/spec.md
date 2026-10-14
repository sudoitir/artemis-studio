# product-identity Specification

## Purpose
The product has one name, Rahdar, in every identity it publishes, and its broker SDK has its own neutral name, OpenBroker, so tools for any broker can depend on it.

## Requirements

### Requirement: One product name everywhere
Every published identity of the product SHALL use the product name **Rahdar** (`rahdar` in identifiers): the Java package root, the Maven artifact, the npm packages, the Docker image, the configuration and environment prefixes, the UI, the docs, the site and the repository. No published artifact, page or default SHALL use "Artemis Studio" as the product's name.

#### Scenario: A fresh install
- **WHEN** an operator pulls the image and starts it with its documented defaults
- **THEN** the UI, the logs, the configuration keys and the environment variables all use the new name

#### Scenario: A search for the old name
- **WHEN** the repositories are searched for the old product name
- **THEN** it appears only where it names history: release notes, archived changes and ADR records of past decisions

### Requirement: The README explains the name
The README and each of its translations SHALL explain the product name near the top: *Rahdar* (Persian راهدار) is *rah* (route) + *dar* (keeper), the road-keeper who keeps every route open and safe.

#### Scenario: A first-time reader
- **WHEN** someone opens the README
- **THEN** they learn what "Rahdar" means and how its two parts map to the product before the install section

### Requirement: The SDK has its own neutral name
The broker SDK SHALL be published as **OpenBroker** (`io.github.sudoitir:openbroker-*`, packages `io.github.sudoitir.openbroker…`), a name distinct from the product's brand and naming no broker vendor, so that tools for any broker can depend on it.

#### Scenario: An outside tool depends on the SDK
- **WHEN** a Kafka tool adds the SDK and its Kafka driver
- **THEN** none of its coordinates or packages carry the product's brand

### Requirement: Old links keep working where the platform allows
After the repository rename, old GitHub URLs SHALL redirect, and the site SHALL be served from the new address. Upgrade notes SHALL list every changed coordinate, image, prefix and URL.

#### Scenario: An old issue link
- **WHEN** someone opens a link to an issue under the old repository name
- **THEN** GitHub redirects them to the same issue under the new name

### Requirement: The site lives on the product's domain
The documentation site SHALL be served from `https://rahdar.dev/` at the root path, with its sitemap, robots file and canonical links on that origin, and the problem-type URIs SHALL live under `https://rahdar.dev/problems/`.

#### Scenario: Opening the docs
- **WHEN** someone opens `https://rahdar.dev/guide/quickstart`
- **THEN** the quickstart is served over HTTPS and its sitemap entry uses the same address

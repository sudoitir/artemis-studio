# ADR-0201: The product is Rahdar and the broker SDK is OpenBroker

- **Status**: accepted
- **Date**: 2026-10-14
- **Deciders**: Mahdi Amirabdollahi

## Context

The product was named "Artemis Studio" (ADR-0001), after the one broker it managed and with a
recorded trademark risk on the ASF's "Artemis" mark. It is becoming an operations console for every
message broker an organisation runs: Kafka, RabbitMQ and ActiveMQ Classic join Artemis behind a
broker SDK (planned change 19c). A name that names one broker misleads every other broker's operator,
and a rename costs more with every user, link and published artifact. The SDK is about to be published
under its own coordinates, and tools for other brokers must be able to depend on it without carrying
the product's brand, as R2DBC, OpenFeature and OpenTelemetry show.

A naming brief (positioning, on-call operators and platform teams as the audience, meaning, sound,
length, spelling, international reading, and no clash with messaging, AI-agent or developer-tool
products) produced candidates that were checked for a free npm scope, Docker Hub repository, GitHub
name and domain, and searched for products of the same name.

## Decision

The product is **Rahdar** (Persian راهدار, "road-keeper": *rah*, route, + *dar*, keeper), the one who
keeps every route open and safe. The broker SDK is **OpenBroker**.

1. Every identity uses the name, with no alias or compatibility path (pre-stable): the Java root
   `io.github.sudoitir.rahdar`, artifactIds `rahdar` and `rahdar-client`, npm `@rahdar/plugin-sdk` and
   `@rahdar/client`, image `sudoit1/rahdar`, configuration `rahdar.*`, environment `RAHDAR_*`, metric
   and MCP tool names, message properties, tables, the plugin manifest (`META-INF/rahdar/plugin.json`,
   its `rahdar` range field), the repository `sudoitir/rahdar` and problem-type URIs under
   `https://rahdar.dev/problems/`. The short name "Studio" was the product's name too and goes with it.
   The groupId stays `io.github.sudoitir`.
2. The extension contract rises to 15: a plugin is rebuilt against the new names.
3. Tables are renamed inside the module baselines (ADR-0192): an existing database is not upgraded, and
   operators start from an empty `rahdar` database.
4. The documentation site moves from `https://sudoitir.github.io/artemis-studio/` to the apex of
   `https://rahdar.dev/` (superseding ADR-0061 D6): `base` is `/`, a `CNAME` binds GitHub Pages, and
   Search Console verifies the domain by DNS, so no verification file ships with the site.
5. The README and its translations explain the name in one line under the title, and the mark is
   redrawn for the name (see Consequences).
6. The SDK's artifacts are `io.github.sudoitir:openbroker-api`, `-support`, `-tck`, `-bom` and one
   `openbroker-<broker>` driver per broker, under the package root `io.github.sudoitir.openbroker`.
7. The product name still lives only in `Branding.java` and `web/src/branding.ts` where code shows it
   (ADR-0001's rule 1 stands); "Artemis" now names only the broker, as nominative use.

## Consequences

- The ASF trademark risk recorded in ADR-0001 is gone: "Artemis" no longer names the product.
- Upgraders change the image, the coordinates, every configuration key and environment variable, and
  start from an empty database; plugins are rebuilt for contract 15. The release note lists all of it.
- Old GitHub URLs redirect; old site URLs under `sudoitir.github.io/artemis-studio/` do not.
- Past ADRs and released changelog files keep the old name: they are records of what was true then.
- The mark is new: **Kept Road**, one road cut through a tile as a lowercase r that runs on past two
  edges, the way kept open. It replaces Artemis' crescent bow. `web/public/favicon.svg` is the single
  master the app, the site and the README use; `brand/` holds the lockups, the constructed wordmark, the
  icon set and the usage guide (`brand/guidelines.md`). A trademark and reverse-image search is still due.

## Alternatives considered

- **Keep "Artemis Studio".** Names one broker and keeps the trademark risk.
- **Signalbox, Switchboard, Patchbay, Busbar, Gantry and other plain words.** Their npm scopes and
  domains are taken.
- **Telex, Chapar, Yardmaster, Watchkeeper, Cueboard, Sigbox, Estafette.** Each clashes with an existing
  messaging, developer-tool or AI-agent product.
- **Interlocking.** Clean, but four syllables.
- **Gozar, Peygham, Kanaal.** Weaker meaning, a religious connotation, or a crowded idea.
- **A rename changeset instead of editing the baselines.** Keeps existing databases, but adds a migration
  the pre-stable policy does not want; the maintainers chose a fresh database.

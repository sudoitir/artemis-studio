# ADR-0001: Project name "Artemis Studio" and its trademark risk

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

The project is a management and observability console for Apache ActiveMQ Artemis.
"Artemis Studio" describes it instantly and the word "Studio" positions it as
operator tooling (cf. Data Studio, Android Studio).

The Apache Software Foundation's trademark policy
(<https://www.apache.org/foundation/marks/>) states that its marks include not
only "Apache ProjectName" but the bare **"ProjectName"** form, and that one "may
not apply [ASF] trademarks to your own products". Nominative use ("a studio *for*
Apache ActiveMQ Artemis") is permitted; naming the product with the mark is not.
Precedent: **Kafka Tool** was renamed to **Offset Explorer** for this reason.

Mahdi Amirabdollahi was presented with this and chose to keep "Artemis Studio".

## Decision

We will ship as **Artemis Studio**, and structure the project so that a rename is
cheap and low-risk:

1. Every user-visible occurrence of the name resolves from **one constant per
   side** — `Branding.java` (`io.github.sudoitir.artemisstudio.Branding`) and
   `web/src/branding.ts`. No other file hard-codes the name.
2. Maven `groupId` is `io.github.sudoitir` (identity-neutral); only the
   `artifactId` and the container image coordinates carry the name.
3. A trademark disclaimer appears in `NOTICE`, the README, and the in-app About
   dialog: *"Apache ActiveMQ and Apache ActiveMQ Artemis are trademarks of the
   Apache Software Foundation. Artemis Studio is an independent project and is not
   produced by, endorsed by, or affiliated with the ASF."*
4. All other references to "Artemis" (docs, tagline, GitHub topics) are
   nominative — describing the broker the tool manages.

### Rename procedure, if ever needed

Change `Branding.PRODUCT_NAME` / `PRODUCT_SHORT_NAME`, the same in `branding.ts`,
the `<title>` in `web/index.html`, the Maven `artifactId`, the image name in
`deploy/compose/*.yaml` and CI, and the repo/org. No source logic changes.

## Consequences

- Accepts a non-zero risk of an ASF request to rename; mitigated to roughly a
  one-day mechanical change.
- SEO for "artemis" competes with the broker's own material; the tagline and docs
  carry the keyword regardless.
- Contributors must not sprinkle the literal string through the code — enforced by
  review and noted in `CLAUDE.md`.

## Alternatives considered

- **Quiver Studio** — zero trademark surface (archery allusion to Artemis the
  archer), org `quivermq` verified free. Rejected by the owner in favour of the
  descriptive name.
- **"Studio for Artemis"** — closer to nominative use but a weak, ungoogleable
  product name that still leans on the mark. Rejected as a half-measure.

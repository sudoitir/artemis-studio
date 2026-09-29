# ADR-0131: The web code is formatted by Prettier

- **Status**: accepted
- **Date**: 2026-09-29
- **Deciders**: Mahdi Amirabdollahi

## Context

The Java is formatted by Palantir Java Format through Spotless and checked at `verify`. The web
code (`web/`: TypeScript, TSX, CSS, JSON) had no formatter. ESLint there has no stylistic rules and
`.editorconfig` sets only indentation, so layout drifted by author, and review time went to
whitespace. About 2,000 lines ran past 100 columns and 500 past 120.

## Decision

**We will format everything under `web/` with Prettier 3, at 120 columns, single quotes and
trailing commas, and check it in CI.**

- `web/.prettierrc.json` holds the three options; `web/.prettierignore` excludes generated and
  vendored files (the API schema, `dist/`, coverage, the lockfile, Markdown).
- `npm run format` writes, `npm run format:check` checks. The frontend CI job runs the check before
  ESLint, and `just fmt` runs Palantir, Prettier and `eslint --fix` in that order.
- 120 columns matches Palantir's width, and of the widths tried it changes the fewest lines.
- The one-time reformat is a single commit listed in `.git-blame-ignore-revs`, so `git blame`
  (and GitHub's blame view) skips it.
- The documentation site is not included: it is Markdown prose, which Prettier would rewrap for
  no reader's benefit.

## Consequences

- Layout is never a review comment again; CI rejects unformatted web code.
- The reformat touches most web files once. Branches open at the time rebase onto it, and
  resolving a conflict means taking theirs and running `npm run format`.
- ESLint stays the correctness and boundary linter; it gains no stylistic rules to fight Prettier.

## Alternatives considered

- **Biome.** Rejected for now: faster, but it would replace ESLint and its boundaries plugin
  (ADR-0074) or run beside it as a second toolchain.
- **Prettier through Spotless.** Rejected: it would tie web formatting to a Maven run and a JVM.
- **ESLint stylistic rules.** Rejected: slower, and every rule is a choice to maintain.

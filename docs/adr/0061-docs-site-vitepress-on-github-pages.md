# ADR-0061: The documentation site is VitePress, built and deployed by GitHub Actions

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

Studio has no web presence. Everything a person could read about it lives in a
single 300-line `README.md` and a `docs/` directory that is only legible to
someone who has already cloned the repository. Three separate consequences
follow, and each one costs the project a different thing.

**Nothing is indexable.** An operator searching for "ActiveMQ Artemis cluster
console" finds Hawtio and a decade of mailing-list threads. A GitHub repository
ranks poorly for a problem statement, and there is no page for Google to rank at
all. The Roadmap already carries "Docs site" as a v1.0 item for this reason.

**The README is doing two jobs badly.** It is simultaneously the thirty-second
pitch to someone deciding whether to try the product and the reference manual for
the environment variables, the MCP tool table and the release model. Neither
audience is served: the evaluator scrolls past a configuration table, and the
operator looking up `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY` scrolls past the pitch.

**The project has three audiences and one language.** Artemis runs in a lot of
places where English is the second language of the person on call. A README in
English only is a reach problem, not a politeness problem.

The reference material itself is not the difficulty — `docs/architecture.md`, the
sixty ADRs and the generated `changelog/` are already Markdown, already reviewed,
and already the source of truth. What is missing is somewhere to publish them.

## Decision

We will build the documentation site with **VitePress**, from sources under
`site/`, and publish it to **GitHub Pages through a dedicated Actions workflow**.

**D1 — VitePress, not a second toolchain.** The repository already runs Vite,
TypeScript and Node 22 for `web/`. VitePress is Vite; it introduces a version to
track, not a build system to learn. It also ships the two things this change
exists to produce — locale-aware routing and `sitemap.xml` generation — in the
box, so neither becomes a plugin we own.

**D2 — The site lives in `site/`, outside `web/`.** `web/` is the shipped SPA and
is baked into the jar by the `frontend` Maven profile. A docs dependency must
never be able to reach the artifact an operator runs. `site/` has its own
`package.json` and its own lockfile, and `./mvnw -Pfrontend package` does not
know it exists.

**D3 — `docs/` stays the single source.** The site does not fork the
architecture document, the ADRs or the changelog. A prebuild step copies them
into the VitePress source tree, which is gitignored. A file that exists in two
places diverges; the copy is generated on every build or it is not there.

**D4 — Three languages, and the README follows the site.** English at the root,
Chinese under `/zh/`, Persian under `/fa/` with `dir: 'rtl'`. The README exists in
the same three, as `README.md`, `README.zh.md` and `README.fa.md`. The README is
now the pitch and the quickstart; everything reference-shaped is on the site and
linked.

**D5 — Deployment is its own workflow.** `.github/workflows/pages.yml`, not a job
inside `ci.yml`. The release job in `ci.yml` commits, tags and pushes atomically
before the image goes to Docker Hub (ADR-0042); a documentation build that can
fail must not be able to interleave with that. The Pages workflow is
path-filtered, so a backend-only change does not rebuild the site.

**D6 — The canonical origin is `https://sudoitir.github.io/artemis-studio/`.**
`base` and the sitemap `hostname` both carry the `/artemis-studio/` sub-path.
`github.io` is on the public suffix list, so Search Console verification is a
URL-prefix property on that sub-path — an HTML file in `site/src/public/` or a
meta tag in the site `head`. Both routes stay open, and a custom domain later is a
`CNAME` file plus two string changes.

## Consequences

- The README can finally be short. The reference material it loses is not
  deleted, it is relocated and linked, and the link is the thing a search engine
  can rank.
- The ADRs become readable by someone who has not cloned the repository. That is
  a genuine change in what this project is: the decisions were always public,
  but they were not reachable.
- Three languages is three times the copy to keep honest. Translations drift the
  moment the product does. We accept that the non-English versions will lag a
  release behind on occasion, and we will not gate a release on them; a stale
  translation is better than no translation, and each one carries a link to the
  English original.
- A second `package.json` and lockfile to update. Dependabot noise, and one more
  thing that can break a green build — mitigated by the path filter, which keeps
  the site's failures out of the way of the product's.
- The site becomes a published artifact with a URL that people bookmark. Moving
  it later — to a custom domain, or to a different generator — is now a
  redirect problem, not a free choice. D6 keeps that cheap on purpose.
- Nothing about the product changes. This is why no OpenSpec change accompanies
  this ADR: `openspec/changes/` records behaviour an operator can observe in
  Studio, and none of this is.

## Alternatives considered

**Docusaurus.** More capable at the size we are not: versioned docs, a plugin
ecosystem, MDX. It brings its own React build and a heavier dependency tree for
what is a landing page and a handful of reference documents. Reconsider if
versioned documentation per release becomes a requirement.

**Hand-written static HTML.** Tempting for a landing page and genuinely the
smallest thing that could work — until the second language, the sitemap, and
sixty ADRs that would each need converting. It is the smallest starting point and
the largest ending one.

**Jekyll on the legacy branch-based Pages source.** No build to maintain, but
deployment becomes a `gh-pages` branch that has to be pushed to, which is a worse
place for CI to write than an artifact upload, and Ruby is a toolchain nothing
else here uses.

**Publishing the README as the site.** Zero new content, and it is what most
projects do. It also fixes nothing: the README's problem is that it is one
document for three audiences, and rendering it at a URL does not split it.

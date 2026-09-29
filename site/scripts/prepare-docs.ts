/**
 * Copy the repository's own Markdown into the VitePress source tree (ADR-0061 D3).
 *
 * `docs/` and `changelog/` are the single source. Nothing under `src/reference/`
 * or `src/public/img/` is committed — it is regenerated on every build, so a
 * decision recorded in an ADR cannot drift from the one the site publishes.
 *
 * `changelog/` holds the notes of every release up to 2026.09.60. Later releases
 * commit nothing to the repository (ADR-0129): their notes are the GitHub release
 * bodies, fetched here, and the release table is rebuilt from both.
 *
 * The only work beyond copying is rewriting the handful of links that point out
 * of the copied tree: `.claude/rules/*` has no page on the site, and the
 * changelog reaches the ADRs through a path that only exists in the repo.
 */
import { cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '../..');
const SRC = resolve(HERE, '../src');
const REFERENCE = join(SRC, 'reference');
const BLOB = 'https://github.com/sudoitir/artemis-studio/blob/main';

/** Applied to every copied file, in order. */
const REWRITES: Array<[RegExp, string]> = [
  // `.claude/rules/*` is repository process, deliberately not published as pages.
  [/\]\((?:\.\.\/)+\.claude\//g, `](${BLOB}/.claude/`],
  // changelog → ADR, which sits one directory over once copied, not two.
  [/\]\(\.\.\/docs\/adr\//g, '](../adr/'],
  // The template's placeholder target is illustrative and resolves to nothing.
  [/\]\(xxxx-\*\.md\)/g, '](#)'],
];

/**
 * Changelog entries are commit bodies, and prose like "static <core> settings" is
 * parsed by VitePress as a Vue element that never closes, failing the build. Escape
 * a tag-like `<` outside fenced blocks and inline code; everything else is untouched.
 */
function escapeBareTags(text: string): string {
  let fenced = false;
  return text
    .split('\n')
    .map((line) => {
      if (/^\s*(```|~~~)/.test(line)) {
        fenced = !fenced;
        return line;
      }
      if (fenced) return line;
      return line
        .split(/(`[^`]*`)/)
        .map((part) => (part.startsWith('`') ? part : part.replace(/<(?=[A-Za-z/!])/g, '&lt;')))
        .join('');
    })
    .join('\n');
}

async function copyMarkdown(
  from: string,
  to: string,
  skip: (name: string) => boolean,
  transform: (text: string) => string = (text) => text,
) {
  await mkdir(to, { recursive: true });
  for (const name of await readdir(from)) {
    if (!name.endsWith('.md') || skip(name)) continue;
    const body = transform(
      REWRITES.reduce((text, [find, put]) => text.replace(find, put), await readFile(join(from, name), 'utf8')),
    );
    // README.md is the directory's index everywhere in this repo; VitePress wants index.md.
    await writeFile(join(to, name === 'README.md' ? 'index.md' : name), body);
  }
}

const CALVER = /^(\d{4})\.(\d{2})\.(\d+)$/;
const RELEASES = 'https://api.github.com/repos/sudoitir/artemis-studio/releases?per_page=100';

type Release = { tag_name: string; body: string | null; published_at: string | null; draft: boolean };

/** Every published release, all pages. `GITHUB_TOKEN` raises the rate limit; CI sets it. */
async function fetchReleases(): Promise<Release[]> {
  const headers: Record<string, string> = { Accept: 'application/vnd.github+json' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const out: Release[] = [];
  for (let url: string | null = RELEASES; url; ) {
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`GitHub releases: HTTP ${res.status}`);
    out.push(...((await res.json()) as Release[]));
    url = /<([^>]+)>;\s*rel="next"/.exec(res.headers.get('link') ?? '')?.[1] ?? null;
  }
  return out.filter((r) => !r.draft);
}

/**
 * Write a page for each release that has no committed file, then rebuild the release table.
 * CI fails rather than publish a changelog that silently stops at the committed files;
 * a local build without network keeps what is committed and says so.
 */
async function addPublishedReleases(dir: string) {
  const committed = new Set((await readdir(dir)).map((name) => name.replace(/\.md$/, '')));
  let releases: Release[] = [];
  try {
    releases = await fetchReleases();
  } catch (error) {
    if (process.env.CI) throw error;
    console.warn(`changelog: releases not fetched (${String(error)}); only committed notes are shown`);
  }
  for (const r of releases) {
    if (!CALVER.test(r.tag_name) || committed.has(r.tag_name)) continue;
    const date = (r.published_at ?? '').slice(0, 10);
    await writeFile(join(dir, `${r.tag_name}.md`), `## ${r.tag_name} — ${date}\n\n${escapeBareTags(r.body ?? '')}\n`);
  }

  const rows: Array<[number[], string, string]> = [];
  for (const name of await readdir(dir)) {
    const version = name.replace(/\.md$/, '');
    const m = CALVER.exec(version);
    if (!m) continue;
    const date = /^##\s+\S+\s+—\s+(\d{4}-\d{2}-\d{2})\s*$/m.exec(await readFile(join(dir, name), 'utf8'))?.[1] ?? '';
    rows.push([m.slice(1).map(Number), version, date]);
  }
  rows.sort(([a], [b]) => b[0] - a[0] || b[1] - a[1] || b[2] - a[2]);
  const table = ['| Version | Date |', '| --- | --- |', ...rows.map(([, v, d]) => `| [${v}](${v}.md) | ${d} |`)];
  const index = join(dir, 'index.md');
  const text = await readFile(index, 'utf8');
  await writeFile(
    index,
    text.replace(
      /<!-- releases:start -->[\s\S]*<!-- releases:end -->/,
      `<!-- releases:start -->\n\n${table.join('\n')}\n\n<!-- releases:end -->`,
    ),
  );
}

await rm(REFERENCE, { recursive: true, force: true });
await rm(join(SRC, 'public/img'), { recursive: true, force: true });

await copyMarkdown(join(REPO, 'docs'), REFERENCE, (name) => name !== 'architecture.md');
// `000-template.md` is a form to fill in, not a decision anyone needs to read.
await copyMarkdown(join(REPO, 'docs/adr'), join(REFERENCE, 'adr'), (name) => name === '000-template.md');
await copyMarkdown(join(REPO, 'changelog'), join(REFERENCE, 'changelog'), () => false, escapeBareTags);

await addPublishedReleases(join(REFERENCE, 'changelog'));

await cp(join(REPO, 'docs/img'), join(SRC, 'public/img'), { recursive: true });
// The product's mark has one home, the app's own public folder; the site and the README use it.
await cp(join(REPO, 'web/public/favicon.svg'), join(SRC, 'public/favicon.svg'));

console.log('prepared src/reference and src/public/img from docs/ and changelog/');

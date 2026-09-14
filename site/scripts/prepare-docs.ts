/**
 * Copy the repository's own Markdown into the VitePress source tree (ADR-0061 D3).
 *
 * `docs/` and `changelog/` are the single source. Nothing under `src/reference/`
 * or `src/public/img/` is committed — it is regenerated on every build, so a
 * decision recorded in an ADR cannot drift from the one the site publishes.
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

await rm(REFERENCE, { recursive: true, force: true });
await rm(join(SRC, 'public/img'), { recursive: true, force: true });

await copyMarkdown(join(REPO, 'docs'), REFERENCE, (name) => name !== 'architecture.md');
// `000-template.md` is a form to fill in, not a decision anyone needs to read.
await copyMarkdown(join(REPO, 'docs/adr'), join(REFERENCE, 'adr'), (name) => name === '000-template.md');
await copyMarkdown(join(REPO, 'changelog'), join(REFERENCE, 'changelog'), () => false, escapeBareTags);

await cp(join(REPO, 'docs/img'), join(SRC, 'public/img'), { recursive: true });

console.log('prepared src/reference and src/public/img from docs/ and changelog/');

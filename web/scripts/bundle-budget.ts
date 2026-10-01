/**
 * The bundle budget, checked against a finished build:
 *
 *   npm run build && npm run check:bundle
 *
 * What a first visit downloads before anything is on screen is the entry chunk and everything
 * `index.html` preloads. This fails when
 *
 *  - the entry chunk is over 350 kB minified, or
 *  - a chunk `index.html` preloads holds one of the heavy libraries (the editor, the syntax
 *    highlighter, the graph canvas, the layout engine, the charts), which a screen loads when it is opened.
 *
 * Module Federation makes the script `index.html` loads a stub that awaits the shared scope and then
 * imports the real entry, so the stub's own `import("./…")` targets are measured with it.
 */
import { readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';

/** The entry chunk's ceiling, in bytes of minified code (Vite's kB are 1000 bytes). */
export const ENTRY_LIMIT = 350_000;

/** A string that is in a chunk when, and only when, it carries the library. */
export const HEAVY: Readonly<Record<string, string>> = {
  codemirror: 'cm-editor',
  shiki: 'ShikiError',
  xyflow: 'react-flow__',
  elk: 'org.eclipse.elk',
  recharts: 'recharts-wrapper',
};

const hrefs = (html: string, pattern: RegExp) =>
  [...html.matchAll(pattern)].map((match) => match[1].replace(/^\//, ''));

/** What the build at `dist` breaks of the budget, one sentence each; empty when it keeps to it. */
export function budgetProblems(dist: string): string[] {
  const html = readFileSync(join(dist, 'index.html'), 'utf8');
  const [script] = hrefs(html, /<script[^>]+type="module"[^>]+src="([^"]+)"/g);
  if (!script) return ['index.html loads no module script'];

  const read = (file: string) => readFileSync(join(dist, file), 'utf8');
  const entryImports = [...read(script).matchAll(/import\(\s*["']\.\/([^"']+)["']\s*\)/g)].map((match) =>
    join(dirname(script), match[1]),
  );

  const problems: string[] = [];
  for (const file of [script, ...entryImports]) {
    const size = statSync(join(dist, file)).size;
    if (size > ENTRY_LIMIT) problems.push(`the entry chunk ${file} is ${size} bytes, over ${ENTRY_LIMIT}`);
  }
  for (const file of [script, ...hrefs(html, /<link[^>]+rel="modulepreload"[^>]+href="([^"]+)"/g)]) {
    const code = read(file);
    for (const [library, signature] of Object.entries(HEAVY)) {
      if (code.includes(signature)) problems.push(`index.html preloads ${file}, which holds ${library}`);
    }
  }
  return problems;
}

if (import.meta.main) {
  const problems = budgetProblems(process.argv[2] ?? 'dist');
  for (const problem of problems) console.error(`bundle budget: ${problem}`);
  if (problems.length > 0) process.exit(1);
  console.log('bundle budget: ok');
}

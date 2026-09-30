import type { SummaryView } from './api.ts';

/** GitHub and the browsers accept far longer URLs, but proxies and some servers stop near 8 000 characters. */
export const MAX_ISSUE_URL = 8000;

export interface BugEnvironment {
  summary: SummaryView | undefined;
  signIn: string[];
  plugins: { id: string; version?: string | null; status?: string | null }[];
  browser: string;
}

export interface BugDescription {
  happened: string;
  expected: string;
  steps: string;
}

/** The environment block of a report, as a Markdown table. Unknown values say so rather than vanish. */
export function environmentMarkdown(env: BugEnvironment): string {
  const s = env.summary;
  const plugin = (p: BugEnvironment['plugins'][number]) => {
    const status = p.status ? ' (' + p.status.toLowerCase() + ')' : '';
    return `${p.id} ${p.version ?? '?'}${status}`;
  };
  const plugins = env.plugins.length ? env.plugins.map(plugin).join(', ') : 'none';
  const rows: [string, string][] = [
    ['Studio', s ? `${s.studioVersion} (plugin contract ${s.contractVersion})` : 'unknown'],
    ['Java', s?.java ?? 'unknown'],
    ['OS', s?.os ?? 'unknown'],
    ['Database', s?.database ?? 'unknown'],
    ['Sign-in', env.signIn.length ? env.signIn.join(', ') : 'unknown'],
    ['Plugins', plugins],
    ['Browser', env.browser],
  ];
  const cell = (v: string) => v.replaceAll('|', String.raw`\|`).replaceAll('\n', ' ');
  return ['| | |', '| --- | --- |', ...rows.map(([k, v]) => `| ${k} | ${cell(v)} |`)].join('\n');
}

/** The whole issue body: the user's description, then the environment. */
export function issueBody(description: BugDescription, environment: string): string {
  const section = (title: string, text: string) => `### ${title}\n\n${text.trim() || '_Not given._'}`;
  return [
    section('What happened', description.happened),
    section('What I expected', description.expected),
    section('Steps to reproduce', description.steps),
    `### Environment\n\n${environment}`,
  ].join('\n\n');
}

/**
 * The link that opens a new issue with the report filled in. When the body would make it too long, the link
 * carries only the title and the caller puts the body on the clipboard instead.
 */
export function issueUrl(projectUrl: string, title: string, body: string): { url: string; bodyInUrl: boolean } {
  const withBody = `${projectUrl}/issues/new?${new URLSearchParams({ title, body })}`;
  if (withBody.length <= MAX_ISSUE_URL) return { url: withBody, bodyInUrl: true };
  return { url: `${projectUrl}/issues/new?${new URLSearchParams({ title })}`, bodyInUrl: false };
}

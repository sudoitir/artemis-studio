/** What Studio says about where a node's management URL came from, why it has none, and how each account fared. */

export type UrlSource = 'SEED' | 'DERIVED' | 'MANUAL';
export type UrlProblem =
  'NO_PATTERN' | 'UNREACHABLE' | 'CREDENTIALS_REJECTED' | 'WRONG_ENDPOINT' | 'TLS_FAILED' | 'OTHER_BROKER';
export type AccountResult = 'ACCEPTED' | 'REJECTED' | 'UNREACHABLE' | 'NOT_TRIED';

const SOURCE: Record<UrlSource, string> = {
  SEED: 'From the registered seed address',
  DERIVED: 'Derived from the management URL pattern',
  MANUAL: 'Management URL set by hand',
};

const PROBLEM: Record<UrlProblem, string> = {
  NO_PATTERN: 'the cluster has no management URL pattern to derive one from',
  UNREACHABLE: 'nothing answered at the address the pattern gives',
  CREDENTIALS_REJECTED: 'the broker at the address the pattern gives rejected the management account',
  WRONG_ENDPOINT: 'the address the pattern gives did not answer as an Artemis management endpoint',
  TLS_FAILED: 'the TLS handshake with the address the pattern gives failed',
  OTHER_BROKER: 'a different broker answered at the address the pattern gives',
};

const ACCOUNT: Record<AccountResult, string> = {
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
  UNREACHABLE: 'Unreachable',
  NOT_TRIED: 'Not tried',
};

export function urlSourceWords(source: UrlSource | null | undefined): string | null {
  return source ? SOURCE[source] : null;
}

/** The reason a node has no management URL, as a clause that follows "because". */
export function urlProblemWords(problem: UrlProblem | null | undefined): string {
  return problem ? PROBLEM[problem] : 'no management URL';
}

export function accountResultWords(result: AccountResult): string {
  return ACCOUNT[result];
}

/** A node's management address: the URL and where it came from, or why there is none. */
export function managementUrlWords(node: {
  managementUrl?: string | null;
  urlSource?: UrlSource | null;
  urlProblem?: UrlProblem | null;
}): string {
  if (node.managementUrl) {
    const source = urlSourceWords(node.urlSource);
    return source ? `${node.managementUrl} (${source.charAt(0).toLowerCase()}${source.slice(1)})` : node.managementUrl;
  }
  return `None: ${urlProblemWords(node.urlProblem)}`;
}

const SECTIONS = [
  ['addresses', 'address', 'addresses'],
  ['addressSettings', 'address setting', 'address settings'],
  ['securitySettings', 'security setting', 'security settings'],
  ['diverts', 'divert', 'diverts'],
] as const;

/** What an adoption would declare, in counts: "3 addresses, 1 address setting, 0 security settings, 0 diverts". */
export function adoptionCountsWords(counts: Record<(typeof SECTIONS)[number][0], number>): string {
  return SECTIONS.map(([key, one, many]) => `${counts[key]} ${counts[key] === 1 ? one : many}`).join(', ');
}

/** The pattern a first seed implies: its own scheme, port and path, with the host left free. */
export function defaultPattern(seedUrl: string | undefined): string {
  if (!seedUrl) return '';
  return seedUrl.replace(/^(https?:\/\/)(?:[^@/]*@)?(\[[^\]]*\]|[^:/?#]+)/i, '$1{host}');
}

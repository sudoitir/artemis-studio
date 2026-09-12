import type {
  ConfigCatalogueView,
  ConfigDeclarationView,
  ConfigDriftFindingView,
  ConfigHazardView,
  ConfigNodeStateView,
  ConfigStepApplyView,
} from '../api/client.ts';
import { prettyKey, prettyValue } from './pretty.ts';

/**
 * The words the configuration screens use. Every state is carried in words;
 * colour is redundant emphasis and appears only where something is wrong
 * (frontend rule: presentation).
 */

export type Section = 'addresses' | 'addressSettings' | 'securitySettings' | 'diverts';

/** The document section a plan step, a drift finding or a hazard names. */
export type WireSection = 'ADDRESS' | 'QUEUE' | 'ADDRESS_SETTING' | 'SECURITY_SETTING' | 'DIVERT';

export const SECTION_LABEL: Record<Section, string> = {
  addresses: 'Addresses and queues',
  addressSettings: 'Address settings',
  securitySettings: 'Security settings',
  diverts: 'Diverts',
};

export const SECTION_TEACHING: Record<Section, string> = {
  addresses:
    'An address receives messages; a queue binds to it and holds them for consumers. Declared ones are created where missing and never deleted by an apply.',
  addressSettings:
    'Per-match limits and policies: what happens when an address fills, where dead letters go, how redelivery backs off. A wildcard match applies to every address under it.',
  securitySettings:
    'Which roles may send, consume, create and manage on the addresses a match covers.',
  diverts:
    'A divert copies — or, when exclusive, takes — the messages arriving at one address and routes them to another.',
};

/** The wire spelling of each document section, for matching findings and hazards to rows. */
export const WIRE_SECTIONS: Record<Section, WireSection[]> = {
  addresses: ['ADDRESS', 'QUEUE'],
  addressSettings: ['ADDRESS_SETTING'],
  securitySettings: ['SECURITY_SETTING'],
  diverts: ['DIVERT'],
};

export function wireSectionLabel(section: string | null | undefined): string {
  switch (section) {
    case 'ADDRESS':
      return 'address';
    case 'QUEUE':
      return 'queue';
    case 'ADDRESS_SETTING':
      return 'address setting';
    case 'SECURITY_SETTING':
      return 'security setting';
    case 'DIVERT':
      return 'divert';
    default:
      return section ? section.toLowerCase().replace(/_/g, ' ') : '';
  }
}

export function nodeStateWords(state: ConfigNodeStateView['state']): {
  text: string;
  tone?: 'warning' | 'danger';
} {
  switch (state) {
    case 'IN_SYNC':
      return { text: 'in sync' };
    case 'DRIFTED':
      return { text: 'drifted', tone: 'warning' };
    case 'NOT_EVALUATED':
      return { text: 'not evaluated' };
    case 'UNREACHABLE':
      return { text: 'unreachable — not evaluated', tone: 'warning' };
    default:
      return { text: state };
  }
}

export function findingKindWords(kind: string): string {
  switch (kind) {
    case 'MISSING':
      return 'Missing';
    case 'DIVERGENT':
      return 'Differs';
    case 'UNDECLARED':
      return 'Undeclared';
    case 'DIVERGENT_QUEUE':
      return 'Queue differs';
    case 'DIVERGENT_ADDRESS':
      return 'Address differs';
    case 'NOT_EVALUATED':
      return 'Not evaluated';
    case 'UNVERIFIABLE':
      return 'Cannot be verified';
    default:
      return kind.replace(/_/g, ' ').toLowerCase();
  }
}

/** What one declared item's drift looks like across the nodes, in one phrase for a table cell. */
export function itemDriftWords(
  declaration: ConfigDeclarationView,
  section: Section,
  key: string,
): { text: string; tone?: 'warning' } {
  const wire = WIRE_SECTIONS[section];
  const live = declaration.nodes.filter((n) => n.live);
  const evaluated = live.filter((n) => n.state === 'IN_SYNC' || n.state === 'DRIFTED');
  if (live.length === 0) return { text: 'no live node' };
  if (evaluated.length === 0) return { text: 'not evaluated' };

  const missing: string[] = [];
  const differs: string[] = [];
  for (const node of evaluated) {
    for (const f of node.findings) {
      if (!wire.includes(f.section as WireSection) || f.key !== key) continue;
      if (f.kind === 'MISSING') missing.push(node.nodeName);
      else if (f.kind !== 'UNDECLARED') differs.push(node.nodeName);
    }
  }
  if (missing.length === 0 && differs.length === 0) {
    const suffix = evaluated.length < live.length ? ` (${live.length - evaluated.length} not evaluated)` : '';
    return { text: `in sync on ${evaluated.length}/${live.length}${suffix}` };
  }
  const parts: string[] = [];
  if (missing.length > 0) parts.push(`missing on ${missing.join(', ')}`);
  if (differs.length > 0) parts.push(`differs on ${differs.join(', ')}`);
  return { text: parts.join('; '), tone: 'warning' };
}

export function hazardClassWords(hazardClass: ConfigHazardView['hazardClass']): string {
  switch (hazardClass) {
    case 'HIGH':
      return 'High';
    case 'MEDIUM':
      return 'Medium';
    case 'LOW':
      return 'Low';
    default:
      return hazardClass;
  }
}

export function stepStatusWords(step: ConfigStepApplyView): {
  text: string;
  tone?: 'warning' | 'danger';
} {
  switch (step.status) {
    case 'WOULD_APPLY':
      return { text: 'would apply' };
    case 'APPLIED':
      return step.verified === 'MISMATCH'
        ? { text: 'applied — read back differs', tone: 'danger' }
        : step.verified === 'UNVERIFIABLE'
          ? { text: 'applied — cannot be verified' }
          : { text: 'applied and verified' };
    case 'ALREADY':
      return { text: 'already as declared' };
    case 'FAILED':
      return { text: 'failed', tone: 'danger' };
    case 'NOT_ATTEMPTED':
      return { text: 'not attempted', tone: 'warning' };
    case 'SKIPPED_NOT_LIVE':
      return { text: 'skipped — not live', tone: 'warning' };
    default:
      return { text: step.status };
  }
}

export function applyOutcomeWords(outcome: 'DRY_RUN' | 'APPLIED' | 'HALTED' | 'FAILED'): {
  text: string;
  tone?: 'warning' | 'danger';
} {
  switch (outcome) {
    case 'DRY_RUN':
      return { text: 'preview' };
    case 'APPLIED':
      return { text: 'applied' };
    case 'HALTED':
      return { text: 'halted', tone: 'warning' };
    case 'FAILED':
      return { text: 'failed', tone: 'danger' };
    default:
      return { text: outcome };
  }
}

export function applyModeWords(mode: ConfigDeclarationView['applyMode']): string {
  return mode === 'CONFIG_MANAGED' ? 'Managed outside Studio' : 'Managed by Studio';
}

/**
 * Why adoption is never automatic. Shown wherever adoption is offered, because an
 * operator who is told drift is advisory will reasonably ask why the product does
 * not close it, and the answer is a decision (ADR-0067 D8), not an omission.
 */
export const WHY_NOT_AUTOMATIC =
  'Studio will not adopt on its own. An adoption declares that whatever the brokers happen to be running right ' +
  'now is intended — including a setting someone changed by hand an hour ago and has not finished thinking ' +
  'about. Only an operator can say that, so drift stays advisory until one does (ADR-0067 D8).';

export const CONFIG_MANAGED_REASON =
  "This cluster's broker.xml is owned by configuration management; applying here would drift from it. " +
  'Copy the broker.xml fragment instead, or change the mode in the declaration header if that is wrong.';

/** A value as the table shows it: a scalar as itself, a list joined, an object as JSON. */
export function valueWords(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (Array.isArray(value)) return value.map(valueWords).join(', ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/** The keys a finding compares, declared beside observed, for a two-column table. */
export function findingRows(
  f: ConfigDriftFindingView,
  catalogue?: ConfigCatalogueView,
): { key: string; declared: string; observed: string; differs: boolean }[] {
  const keys = new Set([...Object.keys(f.declared ?? {}), ...Object.keys(f.observed ?? {})]);
  const isSetting = f.section === 'ADDRESS_SETTING';
  return [...keys].sort().map((key) => {
    const declared = isSetting ? prettyValue(key, f.declared?.[key]) : valueWords(f.declared?.[key]);
    const observed = isSetting ? prettyValue(key, f.observed?.[key]) : valueWords(f.observed?.[key]);
    return { key: isSetting ? prettyKey(key, catalogue) : key, declared, observed, differs: declared !== observed };
  });
}

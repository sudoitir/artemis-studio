import type { ConfigCatalogueView, ConfigDeclarationView, ConfigDriftFindingView, ConfigHazardView, ConfigNodeStateView, ConfigStepApplyView } from './api.ts';
import { prettyKey, prettyValue } from './pretty.ts';

/**
 * The words the configuration screens use. Every state is carried in words;
 * colour is redundant emphasis and appears only where something is wrong
 * (frontend rule: presentation).
 */

export type Section = 'addresses' | 'addressSettings' | 'securitySettings' | 'diverts' | 'bridges';

const SECTIONS: readonly string[] = ['addresses', 'addressSettings', 'securitySettings', 'diverts', 'bridges'];

/** A section named in a URL, or undefined when it names none. */
export function asSection(raw: unknown): Section | undefined {
  return typeof raw === 'string' && SECTIONS.includes(raw) ? (raw as Section) : undefined;
}

/** The document section a plan step, a drift finding or a hazard names. */
export type WireSection = 'ADDRESS' | 'QUEUE' | 'ADDRESS_SETTING' | 'SECURITY_SETTING' | 'DIVERT' | 'BRIDGE';

export const SECTION_LABEL: Record<Section, string> = {
  addresses: 'Addresses and queues',
  addressSettings: 'Address settings',
  securitySettings: 'Security settings',
  diverts: 'Diverts',
  bridges: 'Bridges',
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
  bridges:
    'A bridge forwards a queue to an address on another broker. The broker cannot change one in place, so a changed bridge is applied as a removal and a creation, with nothing forwarded in between.',
};

/** The wire spelling of each document section, for matching findings and hazards to rows. */
export const WIRE_SECTIONS: Record<Section, WireSection[]> = {
  addresses: ['ADDRESS', 'QUEUE'],
  addressSettings: ['ADDRESS_SETTING'],
  securitySettings: ['SECURITY_SETTING'],
  diverts: ['DIVERT'],
  bridges: ['BRIDGE'],
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
    case 'BRIDGE':
      return 'bridge';
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
      return 'Queue differs in a field apply cannot change';
    case 'DIVERGENT_ADDRESS':
      return 'Address keeps a routing type a bound queue uses';
    case 'NOT_EVALUATED':
      return 'Not evaluated';
    case 'UNVERIFIABLE':
      return 'Cannot be verified';
    case 'NOT_CONNECTED':
      return 'Not forwarding — a fault, not drift';
    default:
      return kind.replace(/_/g, ' ').toLowerCase();
  }
}

/**
 * Whether a finding is about this row's item.
 *
 * <p>A queue's finding carries the queue's name, not its address's, so an address
 * row matches its own key and, additionally, the names of the queues declared on
 * it. Comparing keys alone made every queue's drift invisible on the one row that
 * could carry it.
 */
function about(f: ConfigDriftFindingView, wire: WireSection[], key: string, queueKeys: string[]): boolean {
  if (!wire.includes(f.section as WireSection)) return false;
  if (f.key === key) return true;
  return f.section === 'QUEUE' && !!f.key && queueKeys.includes(f.key);
}

/** How a finding names itself on the row: the row's own item is unnamed, a queue is named. */
function labelFor(f: ConfigDriftFindingView, key: string): string {
  return f.section === 'QUEUE' && f.key !== key ? `queue ${f.key} ` : '';
}

/** What one declared item's drift looks like across the nodes, in one phrase for a table cell. */
export function itemDriftWords(
  declaration: ConfigDeclarationView,
  section: Section,
  key: string,
  queueKeys: string[] = [],
): { text: string; tone?: 'warning' } {
  const wire = WIRE_SECTIONS[section];
  const live = declaration.nodes.filter((n) => n.live);
  const evaluated = live.filter((n) => n.state === 'IN_SYNC' || n.state === 'DRIFTED');
  if (live.length === 0) return { text: 'no live node' };
  if (evaluated.length === 0) return { text: 'not evaluated' };

  const missing = new Map<string, string[]>();
  const differs = new Map<string, string[]>();
  // Reported, never counted as drift: a matching bridge that is not forwarding is a
  // fault on the broker, and nothing an apply could write would close it (ADR-0091).
  const faulted = new Map<string, string[]>();
  for (const node of evaluated) {
    for (const f of node.findings) {
      if (!about(f, wire, key, queueKeys) || f.kind === 'UNDECLARED') continue;
      const into = f.kind === 'MISSING' ? missing : f.kind === 'NOT_CONNECTED' ? faulted : differs;
      const label = labelFor(f, key);
      // A node can carry two findings under one label — the address and the queue
      // of the same name — and naming it twice reads as two nodes.
      const named = into.get(label) ?? [];
      if (!named.includes(node.nodeName)) into.set(label, [...named, node.nodeName]);
    }
  }
  const parts: string[] = [];
  const emit = (from: Map<string, string[]>, word: string) =>
    [...from.keys()].sort().forEach((label) => parts.push(`${label}${word} on ${from.get(label)!.join(', ')}`));
  if (missing.size === 0 && differs.size === 0) {
    const suffix = evaluated.length < live.length ? ` (${live.length - evaluated.length} not evaluated)` : '';
    emit(faulted, 'as declared but not forwarding');
    const sync = `in sync on ${evaluated.length}/${live.length}${suffix}`;
    return parts.length === 0 ? { text: sync } : { text: `${sync}; ${parts.join('; ')}`, tone: 'warning' };
  }
  emit(missing, 'missing');
  emit(differs, 'differs');
  emit(faulted, 'as declared but not forwarding');
  return { text: parts.join('; '), tone: 'warning' };
}

/**
 * The plan steps that belong to one declared item, as identifiers (ADR-0087 D2).
 *
 * A step's identifier is `SECTION:key:OP`, so naming all three ops scopes an apply
 * to that item on every node without the screen having to know which op the plan
 * chose. An identifier that matches nothing is ignored by the planner.
 */
export function stepIdsFor(items: { section: WireSection; key: string }[]): string[] {
  return items.flatMap((i) => ['ADD', 'REPLACE', 'REMOVE'].map((op) => `${i.section}:${i.key}:${op}`));
}

/** Every live node's finding about one declared item, newest evaluation as stored. */
export function itemFindings(
  declaration: ConfigDeclarationView,
  section: Section,
  key: string,
  queueKeys: string[] = [],
): { nodeName: string; label: string; finding: ConfigDriftFindingView }[] {
  const wire = WIRE_SECTIONS[section];
  return declaration.nodes
    .filter((n) => n.live)
    .flatMap((n) =>
      n.findings
        .filter((f) => about(f, wire, key, queueKeys) && f.kind !== 'UNDECLARED')
        .map((finding) => ({ nodeName: n.nodeName, label: labelFor(finding, key), finding })),
    );
}

/**
 * How far the declaration has got, as the status bar states it. "Applied to" counts
 * a live node that was evaluated in sync at this revision: a node that agrees with
 * an older revision has not had this one, and a saved-but-unapplied revision must
 * read as zero rather than as agreement.
 */
export function appliedWords(declaration: ConfigDeclarationView): { text: string; tone?: 'warning' } {
  const live = declaration.nodes.filter((n) => n.live);
  if (!declaration.declared) return { text: 'Nothing is declared for this cluster yet' };
  if (live.length === 0) {
    return { text: `Revision ${declaration.revision} — no node is live, so nothing could be applied or compared`, tone: 'warning' };
  }
  const applied = live.filter((n) => n.state === 'IN_SYNC' && n.verifiedRevision === declaration.revision).length;
  return {
    text: `Revision ${declaration.revision} — applied to ${applied} of ${live.length} live node${live.length === 1 ? '' : 's'}`,
    tone: applied === live.length ? undefined : 'warning',
  };
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

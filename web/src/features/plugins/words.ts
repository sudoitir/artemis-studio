import type { PluginInfoView, PluginLicenseView, PluginPlanView, PluginView } from './api.ts';

export const GUIDE_URL = 'https://sudoitir.github.io/artemis-studio/guide/plugins';

/** A plugin's state in words: the column shows this, never a colour alone. */
export const STATUS: Record<string, string> = {
  active: 'Active',
  activating: 'Activating',
  disabled: 'Disabled',
  failed: 'Failed',
  incompatible: 'Incompatible',
  needs_restart: 'Restart required',
  uninstalled: 'Uninstalled, data kept',
};

/** The activation step the server is on, as the progress timeline names it. */
export const STEPS = [
  { key: 'validating', label: 'Checked the jar again' },
  { key: 'draining', label: 'Stopped the running version' },
  { key: 'migrating', label: 'Changed its database' },
  { key: 'starting', label: 'Started it' },
  { key: 'registering', label: 'Connected it to Studio' },
] as const;

/** States that need someone to act; they sort first and carry their fix in the row. */
export function needsAttention(plugin: PluginView): boolean {
  return ['failed', 'incompatible', 'needs_restart'].includes(plugin.status) || plugin.stuck;
}

export function tone(plugin: PluginView): 'danger' | 'warning' | undefined {
  if (plugin.status === 'failed') return 'danger';
  if (needsAttention(plugin)) return 'warning';
  return undefined;
}

/** "3 screens · 2 tools · 1 setting": what a plugin adds, counted. */
export function contributionSummary(info: PluginInfoView): string {
  const c = info.contributions;
  const parts = [
    c.ui ? 'screens' : null,
    count(c.mcpTools.length, 'assistant tool'),
    count(c.permissions.length, 'permission'),
    count(c.settingKeys.length, 'setting'),
    count(c.streamTopics.length, 'live topic'),
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(' · ') : 'Nothing visible';
}

export function count(n: number, noun: string): string | null {
  if (n === 0) return null;
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

/** The exact action the confirm button names: "Update Notes to 1.5.0 (3 database changes)". */
export function actionLabel(plan: PluginPlanView): string {
  const changes = plan.pendingChangesets.length;
  const suffix = count(changes, 'database change') ? ` (${count(changes, 'database change')})` : '';
  const title = plan.info.title;
  if (!plan.fromVersion) return `Install ${title} ${plan.toVersion}${suffix}`;
  if (plan.fromVersion === plan.toVersion) return `Activate ${title} ${plan.toVersion}${suffix}`;
  return `Update ${title} to ${plan.toVersion}${suffix}`;
}

/** What confirming will interrupt, and for how long — said before it can be confirmed. */
export function downtime(plan: PluginPlanView): string {
  switch (plan.activationClass) {
    case 'INSTANT':
      return plan.fromVersion
        ? `No downtime. ${plan.fromVersion} keeps serving until ${plan.toVersion} is ready, then requests switch over.`
        : 'No downtime. Nothing else in Studio is touched.';
    case 'BRIEF_MAINTENANCE':
      return plan.fromVersion
        ? `${plan.info.title} alone pauses for a few seconds while its database changes; its API answers "updating" meanwhile. The rest of Studio keeps working.`
        : `Its database schema is created, then it starts. Nothing else in Studio is touched.`;
    default:
      return plan.restart === 'AUTOMATIC'
        ? 'Studio restarts itself to start it: everyone is disconnected until Studio is back, usually under a minute.'
        : 'It starts the next time Studio restarts. Studio cannot restart itself here; you will be shown the command.';
  }
}

/** The trust status in words: the badge never leans on its colour. */
export const TRUST_LABEL: Record<PluginPlanView['trust']['status'], string> = {
  TRUSTED: 'Verified',
  UNTRUSTED: 'Untrusted key',
  UNSIGNED: 'Unsigned',
};

/** Why the plan needs an explicit "I understand", one sentence per reason the server listed. */
export function acknowledgementReasons(plan: PluginPlanView): string[] {
  return plan.acknowledgements.map((reason) => {
    switch (reason) {
      case 'permissions-added':
        return `It asks for ${plan.diff.permissionsAdded.length === 1 ? 'a permission' : 'permissions'} the installed version did not: ${plan.diff.permissionsAdded.join(', ')}.`;
      case 'signer-changed':
        return `It is signed by a different key than the installed version (${plan.trust.previousFingerprint ?? 'none'} to ${plan.trust.fingerprint ?? 'none'}).`;
      case 'unverified':
        return 'It is unverified: no trusted key vouches for it. An installer allowed unverified plugins.';
      default:
        return reason;
    }
  });
}

export type LicenseState = PluginLicenseView['state'];

/** A license's state in words: the badge and the tab show this, never a colour alone. */
export const LICENSE_LABEL: Record<LicenseState, string> = {
  MISSING: 'No license',
  UNCHECKED: 'Not checked yet',
  VALID: 'Licensed',
  EXPIRING: 'License expiring',
  EXPIRED: 'License expired',
  OVER_LIMIT: 'Over its license limit',
  INVALID: 'License not accepted',
};

/** Everything but a valid license wants someone to look at it. */
export function licenseNeedsAction(license: PluginLicenseView | null | undefined): boolean {
  return !!license && license.state !== 'VALID';
}

/** What an administrator can do about the state, in one sentence. */
export function licenseAdvice(state: LicenseState, title: string, vendor: string): string {
  switch (state) {
    case 'MISSING':
      return `${title} needs a license file. Ask ${vendor} for one and upload it here. What ${title} does without one is its own rule, so check its documentation.`;
    case 'UNCHECKED':
      return `The file is stored and ${title} has not said what it makes of it yet. If this lasts more than a few minutes, ${title} may not be running.`;
    case 'VALID':
      return `${title} accepted this license.`;
    case 'EXPIRING':
      return `${title} accepted this license, and it ends soon. Ask ${vendor} for a new one and upload it before then.`;
    case 'EXPIRED':
      return `This license has ended. Ask ${vendor} for a new one and upload it here.`;
    case 'OVER_LIMIT':
      return `${title} is used beyond what the license allows. Ask ${vendor} for a larger license, or reduce the use.`;
    default:
      return `${title} did not accept this file. Check that it is the file ${vendor} issued for ${title}, unchanged, or ask for a new one.`;
  }
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** "in 12 days", "today" or "3 days ago", for a date a license ends. */
export function expiryNote(expiresAt: string, now: number = Date.now()): string {
  const ahead = new Date(expiresAt).getTime() - now;
  if (ahead >= 0) {
    const days = Math.floor(ahead / DAY_MS);
    if (days >= 2) return `in ${days} days`;
    return days === 1 ? 'tomorrow' : 'today';
  }
  const days = Math.floor(-ahead / DAY_MS);
  if (days >= 2) return `${days} days ago`;
  return days === 1 ? 'yesterday' : 'today';
}

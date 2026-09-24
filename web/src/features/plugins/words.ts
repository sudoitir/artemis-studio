import type { PluginInfoView, PluginPlanView, PluginView } from './api.ts';

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
  return n === 0 ? null : `${n} ${noun}${n === 1 ? '' : 's'}`;
}

/** The exact action the confirm button names: "Update Notes to 1.5.0 (3 database changes)". */
export function actionLabel(plan: PluginPlanView): string {
  const changes = plan.pendingChangesets.length;
  const suffix = changes > 0 ? ` (${changes} database change${changes === 1 ? '' : 's'})` : '';
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

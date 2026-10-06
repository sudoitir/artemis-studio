import type { PermissionView } from './api.ts';

/** A permission added because another one needs it. */
export interface AddedPermission {
  permission: string;
  requiredBy: string;
}

/** A permission that has to go because what it needs is being removed. */
export interface DependentPermission {
  permission: string;
  needs: string;
}

/** Whether the held permissions give `action`, directly or through `*` or `resource:*`. */
function holds(held: ReadonlySet<string>, action: string): boolean {
  if (held.has(action) || held.has('*')) return true;
  const resource = action.split(':')[0];
  return held.has(`${resource}:*`);
}

const requiresOf = (catalogue: readonly PermissionView[]) =>
  new Map(catalogue.map((p) => [p.action, p.requires] as const));

/**
 * `value` with `actions` added, and with every permission they need (and those need) added too. `added`
 * names each permission that came in uninvited, and the one that asked for it.
 */
export function withRequired(
  catalogue: readonly PermissionView[],
  value: readonly string[],
  actions: readonly string[],
): { next: string[]; added: AddedPermission[] } {
  const requires = requiresOf(catalogue);
  const next = [...value];
  const held = new Set(value);
  const added: AddedPermission[] = [];
  const work = actions.filter((a) => !held.has(a));
  for (const action of work) {
    held.add(action);
    next.push(action);
  }
  for (const action of work) {
    for (const need of requires.get(action) ?? []) {
      if (holds(held, need)) continue;
      held.add(need);
      next.push(need);
      added.push({ permission: need, requiredBy: action });
      work.push(need);
    }
  }
  return { next, added };
}

/**
 * The held permissions that would stop working if `removing` went: each one needs something that only a
 * removed permission gave. Following the chain, so a permission that needs a dependent is listed too.
 */
export function dependentsOf(
  catalogue: readonly PermissionView[],
  value: readonly string[],
  removing: readonly string[],
): DependentPermission[] {
  const requires = requiresOf(catalogue);
  const gone = new Set(removing);
  const before = new Set(value);
  const dependents: DependentPermission[] = [];
  for (let changed = true; changed;) {
    changed = false;
    const remaining = new Set(value.filter((a) => !gone.has(a)));
    for (const permission of remaining) {
      const needs = (requires.get(permission) ?? []).find((n) => holds(before, n) && !holds(remaining, n));
      if (needs === undefined) continue;
      gone.add(permission);
      dependents.push({ permission, needs });
      changed = true;
    }
  }
  return dependents;
}

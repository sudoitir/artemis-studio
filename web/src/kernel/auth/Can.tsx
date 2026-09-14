import type { ReactNode } from 'react';

import { useCan } from './useCan.ts';

export interface CanProps {
  permission: string;
  clusterId?: string;
  children: ReactNode;
  fallback?: ReactNode;
}

/** Renders `children` only if the current user holds `permission` (optionally scoped to `clusterId`). */
export function Can({ permission, clusterId, children, fallback = null }: CanProps) {
  const { can } = useCan();
  return can(permission, clusterId) ? <>{children}</> : <>{fallback}</>;
}

import { createContext, useContext } from 'react';

import type { ActionHost } from './types.ts';

export const HostContext = createContext<ActionHost | null>(null);

/** The action host of the surrounding cluster layout (or test harness). */
export function useActionHost(): ActionHost {
  const host = useContext(HostContext);
  if (!host) throw new Error('useActionHost needs an ActionHostProvider above it');
  return host;
}

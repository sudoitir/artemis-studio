import { useMemo } from 'react';
import { useComputedColorScheme, useMantineColorScheme } from '@mantine/core';

type Scheme = 'auto' | 'light' | 'dark';

const NEXT: Record<Scheme, Scheme> = { auto: 'light', light: 'dark', dark: 'auto' };

/**
 * The colour scheme control's state and its name, shared with the command palette's entry. It cycles
 * following the system, light, dark (ADR-0159). The name says where the control goes, not where it is, and
 * while the system decides it also says what the system chose.
 */
export function useColorSchemeToggle() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  // Computed, not the stored value: a stored `auto` could be either scheme. boot-prefs.js has already set
  // it, so the first render reads it rather than waiting for an effect.
  const resolved = useComputedColorScheme('light', { getInitialValueInEffect: false });
  const next = NEXT[colorScheme];
  return useMemo(() => {
    const target = next === 'auto' ? 'system' : next;
    const system = colorScheme === 'auto' ? ` (system is ${resolved})` : '';
    const label = `Use ${target} theme${system}`;
    return { scheme: colorScheme, toggle: () => setColorScheme(next), label };
  }, [colorScheme, next, resolved, setColorScheme]);
}

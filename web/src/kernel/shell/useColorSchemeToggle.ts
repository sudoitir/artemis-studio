import { useMemo } from 'react';
import { useComputedColorScheme, useMantineColorScheme } from '@mantine/core';

/** The toggle and its name, shared with the command palette's entry. */
export function useColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  // Computed, not the stored value: a stored `auto` could be either scheme.
  const current = useComputedColorScheme('dark');
  const next = current === 'dark' ? 'light' : 'dark';
  return useMemo(
    () => ({ toggle: () => setColorScheme(next), label: `Switch to ${next} theme` }),
    [next, setColorScheme],
  );
}

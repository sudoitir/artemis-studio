import { useLocalStorage } from '@mantine/hooks';

/**
 * Sidebar collapse state, persisted per browser (ADR-0034). A boolean local to
 * one component doesn't need a global store (non-negotiable #9) — `localStorage`
 * is the whole state layer. `getInitialValueInEffect: false` reads synchronously
 * so there is no expand→collapse flash on first paint.
 */
export function useNavCollapsed() {
  const [collapsed, setCollapsed] = useLocalStorage<boolean>({
    key: 'as:nav:collapsed',
    defaultValue: false,
    getInitialValueInEffect: false,
  });
  return { collapsed, toggle: () => setCollapsed((c) => !c) };
}

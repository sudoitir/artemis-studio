import { useLocalStorage, useMediaQuery } from '@mantine/hooks';

/**
 * Below this inline size the navigation is its icon rail, whatever the viewer chose (ADR-0164).
 * A rem threshold, so it follows browser zoom: a 1280px window zoomed to 200% is 640 CSS pixels
 * wide and crosses it, while a device class never does.
 */
const NARROW = '(width < 64rem)';

/**
 * Sidebar collapse state, persisted per browser (ADR-0034). A boolean local to
 * one component doesn't need a global store (non-negotiable #9) — `localStorage`
 * is the whole state layer. `getInitialValueInEffect: false` reads synchronously
 * so there is no expand→collapse flash on first paint.
 *
 * `forced` says the rail is the window's doing, not the viewer's: the toggle then changes
 * nothing, and the viewer's own choice is kept for when the window is wide again.
 */
export function useNavCollapsed() {
  const [chosen, setChosen] = useLocalStorage<boolean>({
    key: 'as:nav:collapsed',
    defaultValue: false,
    getInitialValueInEffect: false,
  });
  const forced = useMediaQuery(NARROW, false, { getInitialValueInEffect: false });
  return {
    collapsed: chosen || forced,
    forced,
    toggle: () => {
      if (!forced) setChosen((c) => !c);
    },
  };
}

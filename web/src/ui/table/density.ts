import { useEffect } from 'react';
import { useLocalStorage } from '@mantine/hooks';

export type Density = 'compact' | 'comfortable';

/** The browser-storage key `public/boot-prefs.js` reads before the first paint (ADR-0162). */
export const DENSITY_KEY = 'as:density';

const DEFAULT_DENSITY: Density = 'compact';

function parse(raw: string | undefined): Density {
  try {
    const value: unknown = JSON.parse(raw ?? 'null');
    return value === 'comfortable' || value === 'compact' ? value : DEFAULT_DENSITY;
  } catch {
    return DEFAULT_DENSITY;
  }
}

/** The density a control would switch to, named for what it does, shared by the user menu and the palette. */
export function densityToggleLabel(density: Density): string {
  return `Use ${density === 'compact' ? 'comfortable' : 'compact'} table density`;
}

/**
 * The viewer's table density, for every table (ADR-0162): a per-browser preference, not part of the URL.
 *
 * Storage is read synchronously, so the first render already uses it; `boot-prefs.js` has put the same
 * value on `<html data-density>` before React ran, and the hook keeps that attribute in step afterwards.
 * A consumer that sizes rows in JavaScript reads the height from the theme's `other.density[density]`.
 */
export function useDensity(): [Density, (density: Density) => void] {
  const [density, setDensity] = useLocalStorage<Density>({
    key: DENSITY_KEY,
    defaultValue: DEFAULT_DENSITY,
    getInitialValueInEffect: false,
    deserialize: parse,
  });
  useEffect(() => {
    document.documentElement.dataset.density = density;
  }, [density]);
  return [density, setDensity];
}

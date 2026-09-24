import { describe, expect, it } from 'vitest';

import { viewHotkeys } from '../kernel/keyboard/useKeySequences.ts';
import { FEATURES } from './features.ts';

describe('view letters (ADR-0109)', () => {
  it('are unique among the built-in views, and every one is a single lowercase letter', () => {
    const letters = FEATURES.flatMap((f) => f.nav ?? []).flatMap((n) => (n.hotkey ? [n.hotkey] : []));
    expect(new Set(letters).size).toBe(letters.length);
    for (const letter of letters) expect(letter).toMatch(/^[a-z]$/);
    expect(viewHotkeys(FEATURES).size).toBe(letters.length);
  });
});

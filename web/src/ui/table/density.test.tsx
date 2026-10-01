import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { DENSITY_KEY, densityToggleLabel, useDensity } from './density.ts';

describe('useDensity', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.removeAttribute('data-density');
  });

  it('is compact on a first visit and says so on the page', () => {
    const { result } = renderHook(() => useDensity());

    expect(result.current[0]).toBe('compact');
    expect(document.documentElement.dataset.density).toBe('compact');
  });

  it('reads the stored density on the first render, not after an effect', () => {
    window.localStorage.setItem(DENSITY_KEY, JSON.stringify('comfortable'));
    const renders: string[] = [];

    renderHook(() => {
      const [density] = useDensity();
      renders.push(density);
    });

    expect(renders[0]).toBe('comfortable');
    expect(document.documentElement.dataset.density).toBe('comfortable');
  });

  it('keeps the choice, and the page attribute, in step', () => {
    const { result } = renderHook(() => useDensity());

    act(() => result.current[1]('comfortable'));

    expect(result.current[0]).toBe('comfortable');
    expect(document.documentElement.dataset.density).toBe('comfortable');
    expect(JSON.parse(window.localStorage.getItem(DENSITY_KEY) ?? 'null')).toBe('comfortable');
  });

  it('ignores a stored value outside its two densities', () => {
    window.localStorage.setItem(DENSITY_KEY, JSON.stringify('spacious'));

    const { result } = renderHook(() => useDensity());

    expect(result.current[0]).toBe('compact');
  });

  it('names the density a control would switch to', () => {
    expect(densityToggleLabel('compact')).toBe('Use comfortable table density');
    expect(densityToggleLabel('comfortable')).toBe('Use compact table density');
  });
});

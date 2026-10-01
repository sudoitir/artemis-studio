import { MantineProvider } from '@mantine/core';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';

import { theme } from '../../theme.ts';
import { useColorSchemeToggle } from './useColorSchemeToggle.ts';

function wrapper({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <MantineProvider theme={theme} defaultColorScheme="auto">
      {children}
    </MantineProvider>
  );
}

describe('useColorSchemeToggle', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('starts on the system and goes system, light, dark and round again', () => {
    const { result } = renderHook(() => useColorSchemeToggle(), { wrapper });

    expect(result.current.scheme).toBe('auto');
    expect(result.current.label).toBe('Use light theme (system is light)');

    act(() => result.current.toggle());
    expect(result.current.scheme).toBe('light');
    expect(result.current.label).toBe('Use dark theme');

    act(() => result.current.toggle());
    expect(result.current.scheme).toBe('dark');
    expect(result.current.label).toBe('Use system theme');

    act(() => result.current.toggle());
    expect(result.current.scheme).toBe('auto');
  });

  it('remembers the choice in this browser under the key boot-prefs.js reads', () => {
    const { result } = renderHook(() => useColorSchemeToggle(), { wrapper });

    act(() => result.current.toggle());

    expect(window.localStorage.getItem('mantine-color-scheme-value')).toBe('light');
  });
});

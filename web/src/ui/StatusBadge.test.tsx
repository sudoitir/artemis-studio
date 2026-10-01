import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { StatusBadge } from './StatusBadge.tsx';

describe('StatusBadge', () => {
  it('states the state as text, so it reads without colour', () => {
    renderWithProviders(<StatusBadge tone="danger">Split brain</StatusBadge>);
    expect(screen.getByText('Split brain')).toBeVisible();
  });

  it('is neutral unless told otherwise', () => {
    renderWithProviders(<StatusBadge>Backup</StatusBadge>);
    expect(screen.getByText('Backup')).toHaveAttribute('data-tone', 'neutral');
  });

  it.each(['info', 'warning', 'danger'] as const)('carries the %s tone', (tone) => {
    renderWithProviders(<StatusBadge tone={tone}>State</StatusBadge>);
    expect(screen.getByText('State')).toHaveAttribute('data-tone', tone);
  });
});

describe('the chrome the table measures', () => {
  const css = (file: string) => readFileSync(resolve(process.cwd(), file), 'utf8');
  const declaration = (source: string, property: string) => new RegExp(`${property}:\\s*([^;]+);`).exec(source)?.[1];

  it.each(['padding-inline', 'font-size', 'font-weight'])(
    'is measured with the same %s it is drawn with',
    (property) => {
      const badge = declaration(css('src/ui/StatusBadge.module.css'), property);
      expect(badge).toBeDefined();
      expect(
        declaration(css('src/ui/table/DataTable.module.css').split('.measureCell[data-badge]::before')[1], property),
      ).toBe(badge);
    },
  );

  it('is measured with the same border width', () => {
    expect(css('src/ui/StatusBadge.module.css')).toMatch(/border: 1px solid/);
    expect(css('src/ui/table/DataTable.module.css').split('.measureCell[data-badge]::before')[1]).toMatch(
      /border: 1px solid/,
    );
  });
});

import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { Button, TextInput } from '@mantine/core';

import { renderWithProviders } from '../test/render.tsx';
import { FieldRow } from './FieldRow.tsx';

describe('FieldRow', () => {
  it('holds its fields and the control beside them in one row', () => {
    renderWithProviders(
      <FieldRow>
        <TextInput label="Username" />
        <TextInput label="Password" />
        <Button>Confirm</Button>
      </FieldRow>,
    );
    const username = screen.getByRole('textbox', { name: 'Username' });
    const row = username.closest('.mantine-InputWrapper-root')?.parentElement;
    expect(row).toContainElement(screen.getByRole('textbox', { name: 'Password' }));
    expect(row).toContainElement(screen.getByRole('button', { name: 'Confirm' }));
    expect(row?.children).toHaveLength(3);
  });
});

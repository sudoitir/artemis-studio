import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { Button, TextInput } from '@mantine/core';

import { Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { FieldRow } from './FieldRow.tsx';

const top = (element: HTMLElement) => element.getBoundingClientRect().top;

describe('FieldRow', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('keeps every input box on one line when one field shows a message and the other does not', () => {
      renderThemed(
        <Frame width={640}>
          <FieldRow>
            <TextInput label="Username" error="Enter the username they sign in with." />
            <TextInput label="Email" />
          </FieldRow>
        </Frame>,
        scheme,
      );
      expect(top(screen.getByRole('textbox', { name: 'Username' }))).toBeCloseTo(
        top(screen.getByRole('textbox', { name: 'Email' })),
        0,
      );
    });

    it('lines a button up with the input boxes of the labelled fields beside it', () => {
      renderThemed(
        <Frame width={640}>
          <FieldRow>
            <TextInput label="Add an installer" error="Enter a username." />
            <Button>Add</Button>
          </FieldRow>
        </Frame>,
        scheme,
      );
      expect(top(screen.getByRole('button', { name: 'Add' }))).toBeCloseTo(
        top(screen.getByRole('textbox', { name: 'Add an installer' })),
        0,
      );
    });
  });
});

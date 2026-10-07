import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { TextInput } from '@mantine/core';

import { Frame, renderThemed, SCHEMES } from './browser.tsx';
import { FieldRow } from '../sdk/index.ts';

/**
 * What a plugin gets from the SDK is laid out by the host's own styles, which ship with the host, so a
 * plugin's fields align exactly as Studio's do.
 */
describe.each(SCHEMES)('FieldRow from the SDK in the %s scheme', (scheme) => {
  it('starts both input boxes on one line when one field has a description and the other has none', () => {
    renderThemed(
      <Frame width={640}>
        <FieldRow>
          <TextInput label="Cluster" />
          <TextInput label="Pattern" description="Words separated by dots." />
        </FieldRow>
      </Frame>,
      scheme,
    );

    const top = (name: string) => screen.getByRole('textbox', { name }).getBoundingClientRect().top;
    expect(top('Cluster')).toBeCloseTo(top('Pattern'), 0);
  });
});

import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { TextInput } from '@mantine/core';

import { Frame, renderThemed, SCHEMES } from './browser.tsx';
import { FieldRow, Notice } from '../sdk/index.ts';

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

describe.each(SCHEMES)('Notice from the SDK in the %s scheme', (scheme) => {
  it('states its title in words and announces a warning as a status', () => {
    renderThemed(
      <Frame width={640}>
        <Notice title="Grace period" tone="warning">
          The licence expired; flows stay usable for 14 days.
        </Notice>
      </Frame>,
      scheme,
    );

    expect(screen.getByRole('status')).toHaveTextContent('Grace period');
  });
});

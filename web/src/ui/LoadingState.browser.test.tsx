import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, Frame, renderThemed, SCHEMES } from '../test/browser.tsx';
import { LoadingState } from './LoadingState.tsx';

describe('LoadingState', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations', async () => {
      const { container } = renderThemed(
        <Frame width={960}>
          <LoadingState label="Loading queues" blockSize="12rem" />
        </Frame>,
        scheme,
      );
      expect(await axeViolations(container)).toEqual([]);
    });
  });

  it('holds the size it is given, so what replaces it does not move the page', () => {
    renderThemed(
      <Frame width={960}>
        <LoadingState label="Loading queues" blockSize="12rem" inlineSize="20rem" />
      </Frame>,
      'light',
    );
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('Loading queues');
    const box = status.getBoundingClientRect();
    expect(box.height).toBeGreaterThanOrEqual(12 * 16);
    expect(box.width).toBeGreaterThanOrEqual(20 * 16);
  });

  it('centres its spinner in the space it holds', () => {
    renderThemed(
      <Frame width={960}>
        <LoadingState label="Loading queues" blockSize="12rem" />
      </Frame>,
      'light',
    );
    const status = screen.getByRole('status');
    const box = status.getBoundingClientRect();
    const spinner = status.firstElementChild!.getBoundingClientRect();
    expect(spinner.left + spinner.width / 2).toBeCloseTo(box.left + box.width / 2, 0);
    expect(spinner.top + spinner.height / 2).toBeCloseTo(box.top + box.height / 2, 0);
  });
});

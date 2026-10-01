import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';

import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { axeViolations, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { RegisterCanvas } from './RegisterCanvas.tsx';

/**
 * The registration page's example cards draw real topologies inside a box that is hidden from assistive
 * technology. Nothing inside such a box may take focus: a keyboard user would land on something a screen
 * reader says is not there (`aria-hidden-focus`).
 */
// No network: the shell's manifest query stays pending, which is all a canvas without a cluster needs.
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

describe('RegisterCanvas example cards', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations and no focusable element inside the hidden canvases', async () => {
      const { container } = renderThemed(
        <QueryClientProvider client={new QueryClient()}>
          <FeatureProvider features={[]}>
            <Frame width={480} height={720}>
              <RegisterCanvas preview={undefined} stale={false} shape={null} onSelectShape={() => {}} />
            </Frame>
          </FeatureProvider>
        </QueryClientProvider>,
        scheme,
      );
      await screen.findAllByRole('button', { pressed: false });
      await settle(() => `${container.querySelectorAll('.react-flow__node').length}`);

      const hidden = [...container.querySelectorAll('[aria-hidden="true"]')];
      const focusable = hidden.flatMap((box) => [
        ...box.querySelectorAll('[tabindex]:not([tabindex="-1"]), a, button'),
      ]);
      expect(focusable).toEqual([]);
      expect(await axeViolations(container)).toEqual([]);
    });
  });
});

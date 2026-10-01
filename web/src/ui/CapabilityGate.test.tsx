import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button, MantineProvider } from '@mantine/core';
import { describe, expect, it } from 'vitest';

import { CapabilityGate } from './CapabilityGate.tsx';

const blocked = { kind: 'blocked', reason: 'Not configured.', snippet: '<flag/>' } as const;

function gate(verdict: Parameters<typeof CapabilityGate>[0]['verdict']) {
  return render(
    <MantineProvider>
      <CapabilityGate verdict={verdict} what="creating a queue">
        <Button disabled={verdict.kind === 'blocked'}>New queue</Button>
      </CapabilityGate>
    </MantineProvider>,
  );
}

describe('CapabilityGate', () => {
  it('renders only the control when it is allowed', () => {
    gate({ kind: 'allowed', uncertain: false });
    expect(screen.getByRole('button', { name: 'New queue' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /unavailable/ })).toBeNull();
  });

  it('keeps the blocked control visible and disabled, with a separate explanation button', () => {
    gate(blocked);
    const control = screen.getByRole('button', { name: 'New queue' });
    const why = screen.getByRole('button', { name: 'Why creating a queue is unavailable' });
    expect(control).toBeDisabled();
    expect(control.contains(why)).toBe(false);
    expect(why.contains(control)).toBe(false);
  });

  it('opens the reason and the snippet from the keyboard', async () => {
    const user = userEvent.setup();
    gate(blocked);
    await user.tab();
    expect(screen.getByRole('button', { name: 'Why creating a queue is unavailable' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(await screen.findByText('Not configured.')).toBeInTheDocument();
    expect(screen.getByText('<flag/>')).toBeInTheDocument();
  });
});

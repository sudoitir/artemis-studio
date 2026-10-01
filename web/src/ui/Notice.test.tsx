import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { Notice } from './Notice.tsx';

describe('Notice', () => {
  it('names itself in words, with its body and its next step', () => {
    renderWithProviders(
      <Notice title="Studio needs a restart" tone="warning" action={<button type="button">Restart now</button>}>
        The plugin changes apply after a restart.
      </Notice>,
    );
    const notice = screen.getByRole('status');
    expect(notice).toHaveTextContent('Studio needs a restart');
    expect(notice).toHaveTextContent('The plugin changes apply after a restart.');
    expect(screen.getByRole('button', { name: 'Restart now' })).toBeInTheDocument();
  });

  it.each(['neutral', 'info', 'warning'] as const)('announces tone %s politely', (tone) => {
    renderWithProviders(<Notice title="Heads up" tone={tone} />);
    expect(screen.getByRole('status')).toHaveTextContent('Heads up');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('announces a danger notice assertively', () => {
    renderWithProviders(<Notice title="It cannot be activated yet" tone="danger" />);
    expect(screen.getByRole('alert')).toHaveTextContent('It cannot be activated yet');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('is neutral and politely announced by default, and leaves out an absent body and action', () => {
    renderWithProviders(<Notice title="Nothing to do" />);
    const notice = screen.getByRole('status');
    expect(notice).toHaveAttribute('data-tone', 'neutral');
    expect(notice.children).toHaveLength(1);
  });
});

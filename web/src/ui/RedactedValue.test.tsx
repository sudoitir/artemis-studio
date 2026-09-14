import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import type { components } from '../kernel/api/schema.d.ts';
import { renderWithProviders } from '../test/render.tsx';
import { GovernedValue, RedactionMarks, WithheldNotice } from './RedactedValue.tsx';
import { redactionsAt } from './redactions.ts';

type RedactionView = components['schemas']['RedactionView'];

function redaction(over: Partial<RedactionView> = {}): RedactionView {
  return {
    location: 'PROPERTY',
    path: 'contact',
    dataClass: 'EMAIL',
    label: 'email',
    action: 'REDACT',
    clear: false,
    ...over,
  };
}

describe('RedactedValue', () => {
  it('names a masked value in words, not as a string that resembles the value', () => {
    renderWithProviders(<GovernedValue value="[redacted email]" redactions={[redaction()]} />);
    expect(screen.getByText('[redacted email]')).toBeInTheDocument();
    expect(screen.getByText('Masked: email')).toBeInTheDocument();
  });

  it('marks a dropped credential as dropped', () => {
    renderWithProviders(
      <RedactionMarks redactions={[redaction({ dataClass: 'CREDENTIAL', label: 'credential', action: 'DROP' })]} />,
    );
    expect(screen.getByText('Dropped: credential')).toBeInTheDocument();
  });

  it('says a value shown in clear is sensitive and shown by grant', () => {
    renderWithProviders(<GovernedValue value="jane@example.com" redactions={[redaction({ clear: true })]} />);
    expect(screen.getByText('Sensitive: email, shown by your access')).toBeInTheDocument();
  });

  it('counts repeated redactions of one kind once', () => {
    renderWithProviders(<RedactionMarks redactions={[redaction(), redaction({ path: 'other' })]} />);
    expect(screen.getByText('Masked: email (2)')).toBeInTheDocument();
  });

  it('selects the redactions for one path', () => {
    const all = [redaction(), redaction({ path: 'note' }), redaction({ location: 'BODY', path: 'a.b' })];
    expect(redactionsAt(all, 'PROPERTY', 'contact')).toHaveLength(1);
    expect(redactionsAt(all, 'BODY')).toHaveLength(1);
  });

  it('states why a body was withheld and the setting that changes it', () => {
    renderWithProviders(
      <WithheldNotice
        withheld={[
          {
            location: 'BODY',
            reason: 'Bytes after the first 262144 were not scanned, so they are withheld.',
            settingKey: 'governance.scan-limit',
          },
        ]}
      />,
    );
    expect(screen.getByText('Body withheld')).toBeInTheDocument();
    expect(screen.getByText(/were not scanned/)).toBeInTheDocument();
    expect(screen.getByText('governance.scan-limit')).toBeInTheDocument();
  });

  it('renders nothing when nothing was withheld or redacted', () => {
    const { container } = renderWithProviders(
      <>
        <WithheldNotice withheld={[]} />
        <RedactionMarks redactions={[]} />
      </>,
    );
    expect(container.querySelectorAll('[role="alert"]')).toHaveLength(0);
    expect(screen.queryByText(/Masked|Dropped|Sensitive/)).toBeNull();
  });
});

import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ApproverQuorumNotice } from './ApproverQuorumNotice.tsx';

const status = (over: Record<string, unknown>) =>
  server.use(
    http.get('*/api/v1/gate/status', () =>
      HttpResponse.json({
        armed: true,
        providerId: 'acme-approvals',
        attached: true,
        breakGlass: false,
        enforcing: true,
        approvers: 1,
        quorate: false,
        ...over,
      }),
    ),
  );

describe('ApproverQuorumNotice', () => {
  it('says that only one person may approve, and what to do about it', async () => {
    status({});
    renderWithProviders(<ApproverQuorumNotice />);

    expect(await screen.findByText('Approvals need a second approver')).toBeInTheDocument();
    expect(screen.getByText(/only one person may approve them/)).toBeInTheDocument();
  });

  it.each([
    ['there are enough approvers', { approvers: 2, quorate: true }],
    ['the provider holds nothing yet', { enforcing: false }],
    ['approvals are off', { armed: false, enforcing: false }],
  ])('says nothing when %s', async (_name, over) => {
    status(over);
    renderWithProviders(<ApproverQuorumNotice />);

    // Long enough for the status to have been read.
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(screen.queryByText('Approvals need a second approver')).not.toBeInTheDocument();
  });
});

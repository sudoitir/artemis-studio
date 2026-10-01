import { describe, expect, it } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';

import { ActionHostProvider } from '../../kernel/actions/ActionHost.tsx';
import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import type { SetupFindingView } from './api.ts';
import { FindingCard } from './FindingCard.tsx';

/**
 * A finding card as Chromium lays it out, with a long setting value and a long broker.xml fix. The
 * snippet scrolls, so it must be reachable from the keyboard (axe's scrollable-region-focusable), and
 * the evidence must wrap inside the card instead of widening it.
 */

const LONG = 'org.apache.activemq.artemis.core.server.cluster.ha.ReplicationPrimaryPolicy.quorumVoteWait'.repeat(3);

const finding: SetupFindingView = {
  code: 'HA_SINGLE_PAIR_QUORUM',
  category: 'HIGH_AVAILABILITY',
  severity: 'CRITICAL',
  subject: 'cluster',
  subjectLabel: 'cluster',
  title: 'A single replication pair cannot win a quorum vote, so a network partition splits the brain',
  impact: 'With one primary there is nobody to vote.',
  evidence: [
    { node: 'primary', key: 'HAPolicy', value: LONG },
    { node: 'backup-in-the-secondary-data-centre-with-a-long-name', key: 'HAPolicy', value: 'Replication Backup' },
  ],
  recommendation: 'Coordinate the pair through a distributed lock manager.',
  snippet: `<ha-policy><replication><primary><vote-on-replication-failure>${LONG}</vote-on-replication-failure></primary></replication></ha-policy>`,
  caveats: ['network-check-list is not visible.'],
  appliable: false,
  firstSeenAt: '2026-09-24T10:00:00Z',
  lastSeenAt: '2026-09-24T10:00:00Z',
  stale: false,
  acceptance: null,
} as SetupFindingView;

function card(width: number) {
  return (
    <Frame width={width}>
      <QueryClientProvider client={new QueryClient()}>
        <ActionHostProvider>
          <FindingCard finding={finding} clusterId="c1" canAccept onAccept={() => {}} />
        </ActionHostProvider>
      </QueryClientProvider>
    </Frame>
  );
}

describe.each([contentWidth(1280), 640])('a finding card in a %i px box', (width) => {
  it('keeps the evidence and the fix inside the card', async () => {
    renderThemed(card(width), 'light');
    const article = await screen.findByRole('article');
    const table = await screen.findByRole('table', { name: 'Evidence for HA_SINGLE_PAIR_QUORUM' });
    await settle(() => String(article.getBoundingClientRect().width) + table.getBoundingClientRect().width);
    expect(article.scrollWidth).toBeLessThanOrEqual(article.clientWidth);
    expect(table.parentElement!.scrollWidth).toBeLessThanOrEqual(table.parentElement!.clientWidth);
  });
});

describe.each(SCHEMES)('a finding card in the %s scheme', (scheme) => {
  it('has no accessibility violations', async () => {
    const { container } = renderThemed(card(contentWidth(1280)), scheme);
    await screen.findByRole('article');
    expect(await axeViolations(container)).toEqual([]);
  });
});

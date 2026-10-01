import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { EnvironmentView } from './api.ts';
import { environmentColumns } from './environmentColumns.tsx';
import { Notice } from './Notice.tsx';

/** The environments table as Chromium lays it out with a name as long as an administrator can type, and the notices. */

const LONG = 'customer-order-fulfilment-backlog-on-the-eu-west-primary-broker-pair-'.repeat(2);

const environments: EnvironmentView[] = [
  { id: 'e1', name: LONG, colour: '#e03131', sortOrder: 0 },
  { id: 'e2', name: 'staging', colour: null, sortOrder: 1 },
];

const table = (
  <DataTable
    variant="static"
    label="Environments"
    columns={environmentColumns(
      () => {},
      () => {},
    )}
    data={environments}
    rowKey={(e) => e.id}
    empty={null}
  />
);

describe.each([contentWidth(1280), 640])('the environments table in a %i px box', (width) => {
  it('keeps a long name inside its box', async () => {
    renderThemed(<Frame width={width}>{table}</Frame>, 'light');
    const element = await screen.findByRole('table');
    await settle(() => [...element.querySelectorAll('th')].map((th) => th.getBoundingClientRect().width).join(','));
    const frame = element.parentElement!;
    expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
  });
});

describe.each(SCHEMES)('cluster parts in the %s scheme', (scheme) => {
  it('has no accessibility violations in the environments table', async () => {
    const { container } = renderThemed(<Frame width={contentWidth(1280)}>{table}</Frame>, scheme);
    await screen.findByRole('table');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('has no accessibility violations in a notice of each tone', async () => {
    const { container } = renderThemed(
      <Frame width={contentWidth(1280)}>
        <Notice title="Needs attention" tone="warning">
          Node broker-2 is not reachable.
        </Notice>
        <Notice title="Two nodes are live in one pair" tone="danger" alert>
          Both nodes are serving.
        </Notice>
        <Notice title="Discovered topology">This is what will be saved.</Notice>
      </Frame>,
      scheme,
    );
    await screen.findByText('Needs attention');
    expect(await axeViolations(container)).toEqual([]);
  });
});

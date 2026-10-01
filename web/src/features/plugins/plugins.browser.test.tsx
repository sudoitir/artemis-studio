import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { AuditEventView, PluginInstallerView, TrustedKeyView } from './api.ts';
import { installerColumns, keyColumns } from './dialogColumns.tsx';
import { dataColumns, historyColumns } from './drawerColumns.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { PluginsIntro } from './PluginsPanel.tsx';

/**
 * The plugin tables and notices as Chromium lays them out, with values as long as a real fingerprint or
 * a table name can be: a 95-character fingerprint once ran a table out of its dialog, and the drawer's
 * key and value tables were native tables with a fixed first column.
 */

const FINGERPRINT = Array.from({ length: 32 }, (_, i) => (i * 7 + 11).toString(16).padStart(2, '0').slice(-2)).join(
  ':',
);
const LONG = 'customer-order-fulfilment-backlog-on-the-eu-west-primary-broker-pair-'.repeat(2);

const keys: TrustedKeyView[] = [
  { fingerprint: FINGERPRINT, name: LONG, subject: `CN=${LONG}`, addedAt: '2026-09-11T10:00:00Z', addedBy: LONG },
  { fingerprint: 'AB:CD', name: 'Acme', subject: 'CN=Acme', addedAt: '2026-09-11T10:00:00Z', addedBy: 'ops' },
];

const installers: PluginInstallerView[] = [
  { userId: 'u1', username: LONG, grantedAt: '2026-09-11T10:00:00Z', grantedBy: LONG },
  { userId: 'u2', username: 'ops', grantedAt: '2026-09-11T10:00:00Z', grantedBy: null },
];

const history = [
  { id: 1, ts: '2026-09-11T10:00:00Z', username: LONG, action: 'PLUGIN_ACTIVATE', outcome: 'FAILED', error: LONG },
  { id: 2, ts: '2026-09-11T10:00:00Z', username: null, action: 'PLUGIN_ROLLED_BACK', outcome: 'SUCCESS', error: null },
] as unknown as AuditEventView[];

const TABLES = {
  keys: (
    <DataTable
      variant="static"
      label="Trusted keys"
      columns={keyColumns({ signedPlugins: { [FINGERPRINT]: [LONG, 'acme-notes'] }, onRemove: () => {} })}
      data={keys}
      rowKey={(k) => k.fingerprint}
      empty={null}
    />
  ),
  installers: (
    <DataTable
      variant="static"
      label="Installers"
      columns={installerColumns({ only: false, removing: undefined, onRemove: () => {} })}
      data={installers}
      rowKey={(i) => i.userId}
      empty={null}
    />
  ),
  data: (
    <DataTable
      variant="static"
      label="Tables the plugin keeps"
      columns={dataColumns()}
      data={[
        { name: LONG, estimatedRows: 1_234_567, bytes: 5_000_000_000 },
        { name: 'notes', estimatedRows: -1, bytes: 100 },
      ]}
      rowKey={(t) => t.name}
      empty={null}
    />
  ),
  history: (
    <DataTable
      variant="static"
      label="History of the plugin"
      columns={historyColumns()}
      data={history}
      rowKey={(e) => String(e.id)}
      empty={null}
    />
  ),
};

/** The width of a dialog's table, and of the drawer, at the smallest window. */
const WIDTHS = [contentWidth(1280), 640];

describe.each(WIDTHS)('plugin tables in a %i px box', (width) => {
  it.each(Object.entries(TABLES))('keeps the %s table inside its box with long values', async (_name, table) => {
    renderThemed(<Frame width={width}>{table}</Frame>, 'light');
    const element = await screen.findByRole('table');
    await settle(() => [...element.querySelectorAll('th')].map((th) => th.getBoundingClientRect().width).join(','));
    const frame = element.parentElement!;
    expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth);
  });

  it('wraps a long value of a description list inside its box', async () => {
    renderThemed(
      <Frame width={width}>
        <DescriptionList
          label="About this plugin"
          items={[
            { term: 'Key fingerprint', value: FINGERPRINT },
            { term: 'Signed by', value: LONG },
          ]}
        />
      </Frame>,
      'light',
    );
    const list = await screen.findByRole('group', { name: 'About this plugin' });
    await settle(() => `${list.getBoundingClientRect().width}`);
    expect(list.scrollWidth).toBeLessThanOrEqual(list.clientWidth);
  });
});

describe.each(SCHEMES)('plugin parts in the %s scheme', (scheme) => {
  it.each(Object.entries(TABLES))('has no accessibility violations in the %s table', async (_name, table) => {
    const { container } = renderThemed(<Frame width={contentWidth(1280)}>{table}</Frame>, scheme);
    await screen.findByRole('table');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('has no accessibility violations in the section intro, whose link sits in running text', async () => {
    const { container } = renderThemed(
      <Frame width={contentWidth(1280)}>
        <Section title="Plugins" description={<PluginsIntro />} />
      </Frame>,
      scheme,
    );
    await screen.findByRole('link', { name: 'How plugins work' });
    expect(await axeViolations(container)).toEqual([]);
  });

  it('has no accessibility violations in a notice of each tone', async () => {
    const { container } = renderThemed(
      <Frame width={contentWidth(1280)}>
        <Notice title="Studio needs a restart" tone="warning">
          Notes starts after a restart.
        </Notice>
        <Notice title="It cannot be activated yet" tone="danger">
          It requires acme-core, which is not active.
        </Notice>
        <Notice title="Studio is restarting" tone="info">
          This page reconnects by itself.
        </Notice>
      </Frame>,
      scheme,
    );
    await screen.findByText('Studio needs a restart');
    expect(await axeViolations(container)).toEqual([]);
  });
});

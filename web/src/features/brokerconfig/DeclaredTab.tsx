import { useMemo } from 'react';
import { Button, Stack } from '@mantine/core';

import type {
  ConfigAddressSettingView,
  ConfigAddressView,
  ConfigBridgeView,
  ConfigCatalogueView,
  ConfigDeclarationView,
  ConfigDivertView,
  ConfigSecuritySettingView,
} from './api.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { Section as PageSection } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { AddressEditor } from './AddressEditor.tsx';
import { AddressSettingEditor } from './AddressSettingEditor.tsx';
import { declaredColumns, type DeclaredContext, type DeclaredSpec } from './declaredColumns.tsx';
import { DivertEditor } from './DivertEditor.tsx';
import { SecuritySettingEditor } from './SecuritySettingEditor.tsx';
import { BridgeEditor } from './routing/BridgeEditor.tsx';
import { addressRows, addressSettingRows, bridgeRows, divertRows, securitySettingRows } from './pretty.ts';
import type { ApplyScope } from './ReviewApplyDrawer.tsx';
import { SECTION_LABEL, SECTION_TEACHING, stepIdsFor, type Section } from './words.ts';

const addressSpec: DeclaredSpec<ConfigAddressView> = {
  section: 'addresses',
  noun: 'address',
  nameHeader: 'Address',
  valuesHeader: 'Routing and queues',
  nameOf: (a) => a.name,
  rowsOf: addressRows,
  queueKeysOf: (a) => a.queues.map((q) => q.name),
  // The address and every queue declared on it: applying the address without its queues would leave
  // the row half done.
  scopeOf: (a) => ({
    label: `address ${a.name}`,
    stepIds: stepIdsFor([
      { section: 'ADDRESS', key: a.name },
      ...a.queues.map((q) => ({ section: 'QUEUE' as const, key: q.name })),
    ]),
  }),
};

const securitySpec: DeclaredSpec<ConfigSecuritySettingView> = {
  section: 'securitySettings',
  noun: 'security setting',
  nameHeader: 'Match',
  valuesHeader: 'Role: permissions',
  nameOf: (s) => s.match,
  rowsOf: securitySettingRows,
  noRows: 'no roles',
  scopeOf: (s) => ({
    label: `security setting ${s.match}`,
    stepIds: stepIdsFor([{ section: 'SECURITY_SETTING', key: s.match }]),
  }),
};

const divertSpec: DeclaredSpec<ConfigDivertView> = {
  section: 'diverts',
  noun: 'divert',
  nameHeader: 'Name',
  valuesHeader: 'Declared',
  nameOf: (d) => d.name,
  rowsOf: divertRows,
  scopeOf: (d) => ({ label: `divert ${d.name}`, stepIds: stepIdsFor([{ section: 'DIVERT', key: d.name }]) }),
};

const bridgeSpec: DeclaredSpec<ConfigBridgeView> = {
  section: 'bridges',
  noun: 'bridge',
  nameHeader: 'Name',
  valuesHeader: 'Declared',
  nameOf: (b) => b.name,
  rowsOf: bridgeRows,
  scopeOf: (b) => ({ label: `bridge ${b.name}`, stepIds: stepIdsFor([{ section: 'BRIDGE', key: b.name }]) }),
};

/**
 * One section of the declaration: a table of what is declared beside what the nodes run, or what an
 * empty section is for, and the control that adds an entry (visible, and disabled with its reason
 * when the operator may not).
 */
function DeclaredSection<T>({
  ctx,
  spec,
  items,
  addLabel,
  addGate,
  addDisabled,
}: Readonly<{
  ctx: DeclaredContext;
  spec: DeclaredSpec<T>;
  items: T[];
  addLabel: string;
  addGate: GateVerdict;
  addDisabled: boolean;
}>) {
  const columns = useMemo(() => declaredColumns(ctx, spec), [ctx, spec]);
  const n = items.length;
  const label = SECTION_LABEL[spec.section];
  const add = (
    <CapabilityGate verdict={addGate} what={`adding ${spec.noun}`}>
      <Button variant="default" size="xs" onClick={() => ctx.onEdit(spec.section)} disabled={addDisabled}>
        {addLabel}
      </Button>
    </CapabilityGate>
  );
  return (
    <PageSection
      title={label}
      description={`${n === 0 ? '' : `${n} declared. `}${SECTION_TEACHING[spec.section]}`}
      actions={add}
    >
      {n === 0 ? (
        <EmptyState
          kind="empty"
          title="None declared"
          description="Nothing is declared in this section, so the live nodes are not compared on it."
        />
      ) : (
        <DataTable
          variant="static"
          label={label}
          columns={columns}
          data={items}
          rowKey={spec.nameOf}
          storageKey={`brokerconfig.declared.${spec.section}`}
          height={{ maxRows: n }}
          empty={null}
        />
      )}
    </PageSection>
  );
}

/**
 * The five sections of the declaration, each a table of what is declared beside
 * what the nodes run, with the actions that change either: edit the declaration,
 * or apply this one item to every node (ADR-0087).
 *
 * <p>Nothing is behind a disclosure: the screen's whole job is the comparison,
 * and an accordion made half of it a click away.
 */
export function DeclaredTab({
  declaration,
  catalogue,
  canWrite,
  applyGate,
  onApply,
  openSection,
  openItem,
  onEdit,
}: Readonly<{
  declaration: ConfigDeclarationView;
  catalogue: ConfigCatalogueView | undefined;
  canWrite: boolean;
  /** Whether an apply is possible at all, and why not — never a hidden button. */
  applyGate: GateVerdict;
  onApply: (scope: ApplyScope) => void;
  /** Which editor is open, from the URL: the open resource is navigable state. */
  openSection?: Section;
  openItem?: string;
  onEdit: (section?: Section, item?: string) => void;
}>) {
  const doc = declaration.document;
  const close = () => onEdit(undefined, undefined);
  /** The declared item the URL names, or null — which is the "new item" editor. */
  const openItemIn = <T,>(section: Section, list: T[], keyOfItem: (item: T) => string): T | null =>
    openSection === section && openItem ? (list.find((i) => keyOfItem(i) === openItem) ?? null) : null;

  const ctx = useMemo<DeclaredContext>(
    () => ({ declaration, catalogue, canWrite, applyGate, onEdit, onApply }),
    [declaration, catalogue, canWrite, applyGate, onEdit, onApply],
  );
  const addressSettingSpec = useMemo<DeclaredSpec<ConfigAddressSettingView>>(
    () => ({
      section: 'addressSettings',
      noun: 'address setting',
      nameHeader: 'Match',
      valuesHeader: 'Declared keys',
      nameOf: (s) => s.match,
      rowsOf: (s) => addressSettingRows(s, catalogue),
      noRows: 'no keys — applying resets the entry to the parent match',
      scopeOf: (s) => ({
        label: `address setting ${s.match}`,
        stepIds: stepIdsFor([{ section: 'ADDRESS_SETTING', key: s.match }]),
      }),
    }),
    [catalogue],
  );

  // Disabled with the reason on a focusable control, never a hover-only title.
  const writeGate: GateVerdict = canWrite
    ? { kind: 'allowed', uncertain: false }
    : { kind: 'blocked', reason: 'Needs the "Edit declared configuration" permission on this cluster.' };

  return (
    <>
      <Stack gap="xl">
        <DeclaredSection
          ctx={ctx}
          spec={addressSpec}
          items={doc.addresses}
          addLabel="Add address"
          addGate={writeGate}
          addDisabled={!canWrite}
        />
        <DeclaredSection
          ctx={ctx}
          spec={addressSettingSpec}
          items={doc.addressSettings}
          addLabel="Add address setting"
          addGate={writeGate}
          addDisabled={!canWrite || !catalogue}
        />
        <DeclaredSection
          ctx={ctx}
          spec={securitySpec}
          items={doc.securitySettings}
          addLabel="Add security setting"
          addGate={writeGate}
          addDisabled={!canWrite}
        />
        <DeclaredSection
          ctx={ctx}
          spec={divertSpec}
          items={doc.diverts}
          addLabel="Add divert"
          addGate={writeGate}
          addDisabled={!canWrite}
        />
        <DeclaredSection
          ctx={ctx}
          spec={bridgeSpec}
          items={doc.bridges}
          addLabel="Add bridge"
          addGate={writeGate}
          addDisabled={!canWrite}
        />
      </Stack>

      {catalogue ? (
        <AddressSettingEditor
          declaration={declaration}
          catalogue={catalogue}
          item={openItemIn('addressSettings', doc.addressSettings, (i) => i.match)}
          opened={openSection === 'addressSettings'}
          onClose={close}
        />
      ) : null}
      {catalogue ? (
        <SecuritySettingEditor
          declaration={declaration}
          catalogue={catalogue}
          item={openItemIn('securitySettings', doc.securitySettings, (i) => i.match)}
          opened={openSection === 'securitySettings'}
          onClose={close}
        />
      ) : null}
      <DivertEditor
        declaration={declaration}
        item={openItemIn('diverts', doc.diverts, (i) => i.name)}
        opened={openSection === 'diverts'}
        onClose={close}
      />
      <BridgeEditor
        declaration={declaration}
        item={openItemIn('bridges', doc.bridges, (i) => i.name)}
        opened={openSection === 'bridges'}
        onClose={close}
      />
      <AddressEditor
        declaration={declaration}
        item={openItemIn('addresses', doc.addresses, (i) => i.name)}
        opened={openSection === 'addresses'}
        onClose={close}
      />
    </>
  );
}

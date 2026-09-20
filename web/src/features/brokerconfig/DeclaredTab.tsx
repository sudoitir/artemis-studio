import { Button, Group, Stack, Table, Text } from '@mantine/core';

import type { ConfigCatalogueView, ConfigDeclarationView } from './api.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { AddressEditor } from './AddressEditor.tsx';
import { AddressSettingEditor } from './AddressSettingEditor.tsx';
import { DivertEditor } from './DivertEditor.tsx';
import { SecuritySettingEditor } from './SecuritySettingEditor.tsx';
import classes from './Configuration.module.css';
import { KeyValueList } from './KeyValueList.tsx';
import { addressRows, addressSettingRows, securitySettingRows } from './pretty.ts';
import type { ApplyScope } from './ReviewApplyDrawer.tsx';
import {
  SECTION_LABEL,
  SECTION_TEACHING,
  findingRows,
  itemDriftWords,
  itemFindings,
  stepIdsFor,
  type Section,
} from './words.ts';

/**
 * One declared item's live state, on its own row (ADR-0087 D1): the sentence
 * first — in sync, missing, or differing, with the nodes named — and underneath
 * it the keys that actually differ, declared → observed. Colour is redundant
 * with the words.
 */
function LiveState({
  declaration,
  section,
  itemKey,
  queueKeys,
  catalogue,
}: {
  declaration: ConfigDeclarationView;
  section: Section;
  itemKey: string;
  /** The queues declared on this address: their findings carry their own name, not this row's. */
  queueKeys?: string[];
  catalogue?: ConfigCatalogueView;
}) {
  const { text, tone } = itemDriftWords(declaration, section, itemKey, queueKeys);
  const found = itemFindings(declaration, section, itemKey, queueKeys);
  return (
    <Stack gap={2}>
      <Text size="xs" className={classes.state} data-tone={tone}>
        {text}
      </Text>
      {found.map(({ nodeName, label, finding }, i) => {
        const differing = findingRows(finding, catalogue).filter((r) => r.differs);
        if (differing.length === 0) return null;
        return (
          <div key={`${nodeName}:${i}`} className={classes.kv} data-diff>
            {differing.map((r) => (
              <div key={r.key} className={classes.kvRow} data-differs>
                <span className={classes.kvKey}>
                  {label}
                  {r.key}
                </span>
                <span className={classes.kvValue}>
                  <span className={classes.before}>{r.declared}</span>
                  {' → '}
                  {r.observed}
                </span>
              </div>
            ))}
          </div>
        );
      })}
    </Stack>
  );
}

/**
 * The four sections of the declaration, each a table of what is declared beside
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
}: {
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
}) {
  const doc = declaration.document;
  const close = () => onEdit(undefined, undefined);
  /** The declared item the URL names, or null — which is the "new item" editor. */
  const openItemIn = <T,>(section: Section, list: T[], keyOfItem: (item: T) => string): T | null =>
    openSection === section && openItem ? (list.find((i) => keyOfItem(i) === openItem) ?? null) : null;

  // Disabled with the reason on a focusable wrapper, never a hover-only title.
  const writeGate: GateVerdict = canWrite
    ? { kind: 'allowed', uncertain: false }
    : { kind: 'blocked', reason: 'Needs the "Edit declared configuration" permission on this cluster.' };

  const addButton = (section: Section, label: string) => (
    <CapabilityGate verdict={writeGate}>
      <Button
        variant="default"
        size="xs"
        onClick={() => onEdit(section, undefined)}
        disabled={!canWrite || (section === 'addressSettings' && !catalogue)}
      >
        {label}
      </Button>
    </CapabilityGate>
  );

  const actions = (section: Section, itemKey: string, name: string, scope: ApplyScope) => (
    <Group gap={4} justify="flex-end" wrap="nowrap">
      <Button
        variant="subtle"
        size="compact-xs"
        onClick={() => onEdit(section, itemKey)}
        aria-label={`${canWrite ? 'Edit' : 'View'} ${name}`}
      >
        {canWrite ? 'Edit' : 'View'}
      </Button>
      <CapabilityGate verdict={applyGate} what={`applying ${name}`}>
        <Button
          variant="subtle"
          size="compact-xs"
          onClick={() => onApply(scope)}
          disabled={applyGate.kind === 'blocked'}
          aria-label={`Apply ${name}`}
        >
          Apply this
        </Button>
      </CapabilityGate>
    </Group>
  );

  const heading = (section: Section, n: number) => (
    <Stack gap={2}>
      <Group gap="sm" align="baseline">
        <Text size="sm" fw={600}>
          {SECTION_LABEL[section]}
        </Text>
        <Text size="xs" c="dimmed">
          {n === 0 ? 'none declared' : `${n} declared`}
        </Text>
      </Group>
      <Text size="xs" c="dimmed">
        {SECTION_TEACHING[section]}
      </Text>
    </Stack>
  );

  return (
    <>
      <Stack gap="lg">
        <Stack gap="xs">
          {heading('addresses', doc.addresses.length)}
          {doc.addresses.length > 0 ? (
            <Table fz="xs" verticalSpacing={4}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Address</Table.Th>
                  <Table.Th>Routing and queues</Table.Th>
                  <Table.Th>On the live nodes</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {doc.addresses.map((a) => (
                  <Table.Tr key={a.name}>
                    <Table.Td>{a.name}</Table.Td>
                    <Table.Td>
                      <KeyValueList rows={addressRows(a)} />
                    </Table.Td>
                    <Table.Td>
                      <LiveState
                        declaration={declaration}
                        section="addresses"
                        itemKey={a.name}
                        queueKeys={a.queues.map((q) => q.name)}
                        catalogue={catalogue}
                      />
                    </Table.Td>
                    <Table.Td className={classes.actionCell}>
                      {actions('addresses', a.name, `address ${a.name}`, {
                        label: `address ${a.name}`,
                        // The address and every queue declared on it: applying the
                        // address without its queues would leave the row half done.
                        stepIds: stepIdsFor([
                          { section: 'ADDRESS', key: a.name },
                          ...a.queues.map((q) => ({ section: 'QUEUE' as const, key: q.name })),
                        ]),
                      })}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          ) : null}
          <div>{addButton('addresses', 'Add address')}</div>
        </Stack>

        <Stack gap="xs">
          {heading('addressSettings', doc.addressSettings.length)}
          {doc.addressSettings.length > 0 ? (
            <Table fz="xs" verticalSpacing={4}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Match</Table.Th>
                  <Table.Th>Declared keys</Table.Th>
                  <Table.Th>On the live nodes</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {doc.addressSettings.map((s) => (
                  <Table.Tr key={s.match}>
                    <Table.Td>{s.match}</Table.Td>
                    <Table.Td>
                      <KeyValueList
                        rows={addressSettingRows(s, catalogue)}
                        empty="no keys — applying resets the entry to the parent match"
                      />
                    </Table.Td>
                    <Table.Td>
                      <LiveState
                        declaration={declaration}
                        section="addressSettings"
                        itemKey={s.match}
                        catalogue={catalogue}
                      />
                    </Table.Td>
                    <Table.Td className={classes.actionCell}>
                      {actions('addressSettings', s.match, `address setting ${s.match}`, {
                        label: `address setting ${s.match}`,
                        stepIds: stepIdsFor([{ section: 'ADDRESS_SETTING', key: s.match }]),
                      })}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          ) : null}
          <div>{addButton('addressSettings', 'Add address setting')}</div>
        </Stack>

        <Stack gap="xs">
          {heading('securitySettings', doc.securitySettings.length)}
          {doc.securitySettings.length > 0 ? (
            <Table fz="xs" verticalSpacing={4}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Match</Table.Th>
                  <Table.Th>Role: permissions</Table.Th>
                  <Table.Th>On the live nodes</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {doc.securitySettings.map((s) => (
                  <Table.Tr key={s.match}>
                    <Table.Td>{s.match}</Table.Td>
                    <Table.Td>
                      <KeyValueList rows={securitySettingRows(s)} empty="no roles" />
                    </Table.Td>
                    <Table.Td>
                      <LiveState
                        declaration={declaration}
                        section="securitySettings"
                        itemKey={s.match}
                        catalogue={catalogue}
                      />
                    </Table.Td>
                    <Table.Td className={classes.actionCell}>
                      {actions('securitySettings', s.match, `security setting ${s.match}`, {
                        label: `security setting ${s.match}`,
                        stepIds: stepIdsFor([{ section: 'SECURITY_SETTING', key: s.match }]),
                      })}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          ) : null}
          <div>{addButton('securitySettings', 'Add security setting')}</div>
        </Stack>

        <Stack gap="xs">
          {heading('diverts', doc.diverts.length)}
          {doc.diverts.length > 0 ? (
            <Table fz="xs" verticalSpacing={4}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Name</Table.Th>
                  <Table.Th>Routes</Table.Th>
                  <Table.Th>Effect</Table.Th>
                  <Table.Th>On the live nodes</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {doc.diverts.map((d) => (
                  <Table.Tr key={d.name}>
                    <Table.Td>{d.name}</Table.Td>
                    <Table.Td aria-label={`from ${d.address} to ${d.forwardingAddress}`}>
                      {d.address} → {d.forwardingAddress}
                    </Table.Td>
                    <Table.Td>{d.exclusive ? 'takes the message' : 'copies the message'}</Table.Td>
                    <Table.Td>
                      <LiveState declaration={declaration} section="diverts" itemKey={d.name} catalogue={catalogue} />
                    </Table.Td>
                    <Table.Td className={classes.actionCell}>
                      {actions('diverts', d.name, `divert ${d.name}`, {
                        label: `divert ${d.name}`,
                        stepIds: stepIdsFor([{ section: 'DIVERT', key: d.name }]),
                      })}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          ) : null}
          <div>{addButton('diverts', 'Add divert')}</div>
        </Stack>
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
      <AddressEditor
        declaration={declaration}
        item={openItemIn('addresses', doc.addresses, (i) => i.name)}
        opened={openSection === 'addresses'}
        onClose={close}
      />
    </>
  );
}

import { useState } from 'react';
import { Accordion, Button, Group, Stack, Table, Text } from '@mantine/core';

import type {
  ConfigAddressSettingView,
  ConfigAddressView,
  ConfigCatalogueView,
  ConfigDeclarationView,
  ConfigDivertView,
  ConfigSecuritySettingView,
} from '../api/client.ts';
import { CapabilityGate } from '../shared/CapabilityGate.tsx';
import type { GateVerdict } from '../shared/capabilityGate.ts';
import { AddressEditor } from './AddressEditor.tsx';
import { AddressSettingEditor } from './AddressSettingEditor.tsx';
import { DivertEditor } from './DivertEditor.tsx';
import { SecuritySettingEditor } from './SecuritySettingEditor.tsx';
import classes from './Configuration.module.css';
import { KeyValueList } from './KeyValueList.tsx';
import { addressRows, addressSettingRows, securitySettingRows } from './pretty.ts';
import { SECTION_LABEL, SECTION_TEACHING, itemDriftWords, type Section } from './words.ts';

type Editing =
  | { section: 'addresses'; item: ConfigAddressView | null }
  | { section: 'addressSettings'; item: ConfigAddressSettingView | null }
  | { section: 'securitySettings'; item: ConfigSecuritySettingView | null }
  | { section: 'diverts'; item: ConfigDivertView | null }
  | null;

/** A declared item's drift, in words, never colour alone. */
function Drift({ declaration, section, itemKey }: { declaration: ConfigDeclarationView; section: Section; itemKey: string }) {
  const { text, tone } = itemDriftWords(declaration, section, itemKey);
  return (
    <Text size="xs" className={classes.state} data-tone={tone}>
      {text}
    </Text>
  );
}

/**
 * The four sections of the declaration, each a table with a drift column in
 * words and an edit action that opens the section's editor in a drawer. Focus
 * returns to the row's button when the drawer closes (Mantine's default).
 */
export function DeclaredTab({
  declaration,
  catalogue,
  canWrite,
  openSection,
  onSectionChange,
}: {
  declaration: ConfigDeclarationView;
  catalogue: ConfigCatalogueView | undefined;
  canWrite: boolean;
  openSection: Section | undefined;
  onSectionChange: (section: Section | undefined) => void;
}) {
  const [editing, setEditing] = useState<Editing>(null);
  const doc = declaration.document;
  const close = () => setEditing(null);

  // Disabled with the reason on a focusable wrapper, never a hover-only title.
  const writeGate: GateVerdict = canWrite
    ? { kind: 'allowed', uncertain: false }
    : { kind: 'blocked', reason: 'Needs the "Edit declared configuration" permission on this cluster.' };

  const addButton = (section: Section, label: string) => (
    <CapabilityGate verdict={writeGate}>
      <Button
        variant="default"
        size="xs"
        onClick={() => setEditing({ section, item: null } as Editing)}
        disabled={!canWrite || (section === 'addressSettings' && !catalogue)}
      >
        {label}
      </Button>
    </CapabilityGate>
  );

  const editButton = (section: Section, item: unknown, name: string) => (
    <Button
      variant="subtle"
      size="compact-xs"
      onClick={() => setEditing({ section, item } as Editing)}
      aria-label={`Edit ${name}`}
    >
      {canWrite ? 'Edit' : 'View'}
    </Button>
  );

  const count = (n: number) => (n === 0 ? 'none declared' : `${n} declared`);

  return (
    <>
      <Accordion
        multiple={false}
        value={openSection ?? null}
        onChange={(v) => onSectionChange((v as Section | null) ?? undefined)}
        variant="separated"
      >
        <Accordion.Item value="addresses">
          <Accordion.Control>
            <Group justify="space-between" pr="sm">
              <Text size="sm" fw={600}>
                {SECTION_LABEL.addresses}
              </Text>
              <Text size="xs" c="dimmed">
                {count(doc.addresses.length)}
              </Text>
            </Group>
          </Accordion.Control>
          <Accordion.Panel>
            <Stack gap="xs">
              <Text size="xs" c="dimmed">
                {SECTION_TEACHING.addresses}
              </Text>
              {doc.addresses.length > 0 ? (
                <Table fz="xs" verticalSpacing={4}>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Address</Table.Th>
                      <Table.Th>Routing and queues</Table.Th>
                      <Table.Th>Drift</Table.Th>
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
                          <Drift declaration={declaration} section="addresses" itemKey={a.name} />
                        </Table.Td>
                        <Table.Td className={classes.actionCell}>{editButton('addresses', a, `address ${a.name}`)}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              ) : null}
              <div>{addButton('addresses', 'Add address')}</div>
            </Stack>
          </Accordion.Panel>
        </Accordion.Item>

        <Accordion.Item value="addressSettings">
          <Accordion.Control>
            <Group justify="space-between" pr="sm">
              <Text size="sm" fw={600}>
                {SECTION_LABEL.addressSettings}
              </Text>
              <Text size="xs" c="dimmed">
                {count(doc.addressSettings.length)}
              </Text>
            </Group>
          </Accordion.Control>
          <Accordion.Panel>
            <Stack gap="xs">
              <Text size="xs" c="dimmed">
                {SECTION_TEACHING.addressSettings}
              </Text>
              {doc.addressSettings.length > 0 ? (
                <Table fz="xs" verticalSpacing={4}>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Match</Table.Th>
                      <Table.Th>Declared keys</Table.Th>
                      <Table.Th>Drift</Table.Th>
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
                          <Drift declaration={declaration} section="addressSettings" itemKey={s.match} />
                        </Table.Td>
                        <Table.Td className={classes.actionCell}>
                          {editButton('addressSettings', s, `address setting ${s.match}`)}
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              ) : null}
              <div>{addButton('addressSettings', 'Add address setting')}</div>
            </Stack>
          </Accordion.Panel>
        </Accordion.Item>

        <Accordion.Item value="securitySettings">
          <Accordion.Control>
            <Group justify="space-between" pr="sm">
              <Text size="sm" fw={600}>
                {SECTION_LABEL.securitySettings}
              </Text>
              <Text size="xs" c="dimmed">
                {count(doc.securitySettings.length)}
              </Text>
            </Group>
          </Accordion.Control>
          <Accordion.Panel>
            <Stack gap="xs">
              <Text size="xs" c="dimmed">
                {SECTION_TEACHING.securitySettings}
              </Text>
              {doc.securitySettings.length > 0 ? (
                <Table fz="xs" verticalSpacing={4}>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Match</Table.Th>
                      <Table.Th>Role: permissions</Table.Th>
                      <Table.Th>Drift</Table.Th>
                      <Table.Th />
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {doc.securitySettings.map((s) => {
                      return (
                        <Table.Tr key={s.match}>
                          <Table.Td>{s.match}</Table.Td>
                          <Table.Td>
                            <KeyValueList rows={securitySettingRows(s)} empty="no roles" />
                          </Table.Td>
                          <Table.Td>
                            <Drift declaration={declaration} section="securitySettings" itemKey={s.match} />
                          </Table.Td>
                          <Table.Td className={classes.actionCell}>
                            {editButton('securitySettings', s, `security setting ${s.match}`)}
                          </Table.Td>
                        </Table.Tr>
                      );
                    })}
                  </Table.Tbody>
                </Table>
              ) : null}
              <div>{addButton('securitySettings', 'Add security setting')}</div>
            </Stack>
          </Accordion.Panel>
        </Accordion.Item>

        <Accordion.Item value="diverts">
          <Accordion.Control>
            <Group justify="space-between" pr="sm">
              <Text size="sm" fw={600}>
                {SECTION_LABEL.diverts}
              </Text>
              <Text size="xs" c="dimmed">
                {count(doc.diverts.length)}
              </Text>
            </Group>
          </Accordion.Control>
          <Accordion.Panel>
            <Stack gap="xs">
              <Text size="xs" c="dimmed">
                {SECTION_TEACHING.diverts}
              </Text>
              {doc.diverts.length > 0 ? (
                <Table fz="xs" verticalSpacing={4}>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Name</Table.Th>
                      <Table.Th>Routes</Table.Th>
                      <Table.Th>Effect</Table.Th>
                      <Table.Th>Drift</Table.Th>
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
                          <Drift declaration={declaration} section="diverts" itemKey={d.name} />
                        </Table.Td>
                        <Table.Td className={classes.actionCell}>{editButton('diverts', d, `divert ${d.name}`)}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              ) : null}
              <div>{addButton('diverts', 'Add divert')}</div>
            </Stack>
          </Accordion.Panel>
        </Accordion.Item>
      </Accordion>

      {catalogue ? (
        <AddressSettingEditor
          declaration={declaration}
          catalogue={catalogue}
          item={editing?.section === 'addressSettings' ? editing.item : null}
          opened={editing?.section === 'addressSettings'}
          onClose={close}
        />
      ) : null}
      {catalogue ? (
        <SecuritySettingEditor
          declaration={declaration}
          catalogue={catalogue}
          item={editing?.section === 'securitySettings' ? editing.item : null}
          opened={editing?.section === 'securitySettings'}
          onClose={close}
        />
      ) : null}
      <DivertEditor
        declaration={declaration}
        item={editing?.section === 'diverts' ? editing.item : null}
        opened={editing?.section === 'diverts'}
        onClose={close}
      />
      <AddressEditor
        declaration={declaration}
        item={editing?.section === 'addresses' ? editing.item : null}
        opened={editing?.section === 'addresses'}
        onClose={close}
      />
    </>
  );
}

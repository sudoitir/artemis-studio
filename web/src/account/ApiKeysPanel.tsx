import { useMemo, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Checkbox,
  CopyButton,
  Group,
  Modal,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { IconCopy, IconTrash } from '@tabler/icons-react';

import {
  useClusters,
  useCreateToken,
  usePermissionsCatalogue,
  useRevokeToken,
  useTokens,
  type TokenGrantRequest,
} from '../api/client.ts';
import { useCan } from '../auth/Can.tsx';

const GLOBAL = 'GLOBAL';

/**
 * Personal API keys (ADR-0039), and the only place a key is minted. A key is the
 * credential the MCP surface authenticates with (ADR-0046), which is why the
 * permission picker matters: a key created with no grants can sign in and do
 * nothing, and a model given one reports "no cluster visible" rather than
 * anything a user can act on.
 *
 * <p>The picker offers only permissions the signed-in user actually holds. The
 * server intersects the requested grants with the owner's live grants anyway, so
 * this cannot escalate — offering more would just mint keys that silently lose
 * half of what was ticked.
 */
export function ApiKeysPanel() {
  const tokens = useTokens();
  const create = useCreateToken();
  const revoke = useRevokeToken();
  const catalogue = usePermissionsCatalogue();
  const clusters = useClusters();
  const { can } = useCan();

  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [scope, setScope] = useState<string>(GLOBAL);
  const [chosen, setChosen] = useState<string[]>([]);
  const [mintedValue, setMintedValue] = useState<string | null>(null);

  const clusterId = scope === GLOBAL ? undefined : scope;

  // What the user can actually delegate at the selected scope. A wildcard grant
  // makes every catalogued permission available; `can` resolves that.
  const available = useMemo(
    () => (catalogue.data ?? []).filter((p) => can(p.action, clusterId)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [catalogue.data, clusterId],
  );

  const close = () => {
    setCreating(false);
    setMintedValue(null);
    setName('');
    setChosen([]);
    setScope(GLOBAL);
  };

  const submit = () => {
    const grants: TokenGrantRequest[] = chosen.map((action) => ({
      action,
      scopeType: scope === GLOBAL ? GLOBAL : 'CLUSTER',
      scopeId: scope === GLOBAL ? null : scope,
    }));
    create.mutate(
      { name, expiresAt: undefined, grants },
      { onSuccess: (created) => setMintedValue(created.value) },
    );
  };

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          Keys authenticate as you, narrowed to no more than your own grants.
        </Text>
        <Button size="xs" onClick={() => setCreating(true)}>
          New key
        </Button>
      </Group>

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Name</Table.Th>
            <Table.Th>Prefix</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Last used</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(tokens.data ?? []).map((t) => (
            <Table.Tr key={t.id}>
              <Table.Td>
                <Text size="sm">{t.name}</Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs" ff="monospace" c="dimmed">
                  {t.prefix}
                </Text>
              </Table.Td>
              <Table.Td>
                {t.revokedAt ? (
                  <Badge size="xs" color="red" variant="light">
                    revoked
                  </Badge>
                ) : t.expiresAt && new Date(t.expiresAt) < new Date() ? (
                  <Badge size="xs" color="orange" variant="light">
                    expired
                  </Badge>
                ) : (
                  <Badge size="xs" color="green" variant="light">
                    active
                  </Badge>
                )}
              </Table.Td>
              <Table.Td>
                <Text size="xs" c="dimmed">
                  {t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString() : 'never'}
                </Text>
              </Table.Td>
              <Table.Td>
                {!t.revokedAt ? (
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    onClick={() => revoke.mutate(t.id)}
                    aria-label={`Revoke ${t.name}`}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                ) : null}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal opened={creating} onClose={close} title="New API key">
        {mintedValue ? (
          <Stack gap="sm">
            <Alert color="yellow">
              This value is shown once. Copy it now — it cannot be retrieved again.
            </Alert>
            <Group>
              <TextInput
                value={mintedValue}
                readOnly
                style={{ flex: 1 }}
                ff="monospace"
                aria-label="API key"
              />
              <CopyButton value={mintedValue}>
                {({ copy }) => (
                  <ActionIcon onClick={copy} aria-label="Copy key">
                    <IconCopy size={16} />
                  </ActionIcon>
                )}
              </CopyButton>
            </Group>
          </Stack>
        ) : (
          <Stack gap="sm">
            <TextInput
              label="Name"
              value={name}
              onChange={(e) => setName(e.currentTarget.value)}
              required
            />
            <Select
              label="Scope"
              description="Where the key's permissions apply."
              value={scope}
              onChange={(v) => {
                setScope(v ?? GLOBAL);
                setChosen([]);
              }}
              data={[
                { value: GLOBAL, label: 'Global — every cluster' },
                ...(clusters.data ?? []).map((c) => ({ value: c.id, label: c.name })),
              ]}
            />
            <Checkbox.Group
              label="Permissions"
              description="Only what you hold at this scope is offered."
              value={chosen}
              onChange={setChosen}
            >
              <ScrollArea.Autosize mah={220} mt="xs">
                <Stack gap="xs">
                  {available.map((p) => (
                    <Checkbox key={p.action} value={p.action} label={p.label} />
                  ))}
                  {available.length === 0 ? (
                    <Text size="xs" c="dimmed">
                      You hold nothing at this scope, so a key made here could do nothing.
                    </Text>
                  ) : null}
                </Stack>
              </ScrollArea.Autosize>
            </Checkbox.Group>
            <Button
              loading={create.isPending}
              disabled={!name || chosen.length === 0}
              onClick={submit}
            >
              Create
            </Button>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}

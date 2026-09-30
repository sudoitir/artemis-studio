import { useMemo, useState } from 'react';
import { Alert, Button, MultiSelect, Select, Stack, Text, TextInput } from '@mantine/core';

import { useClusters } from '../clusters/index.ts';
import { PermissionPicker, usePermissionsCatalogue } from '../security/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { serverNow } from '../../kernel/time/time.ts';
import { useCreateToken, useMcpTools, useTokenPolicy, type CreatedTokenView, type TokenGrantRequest } from './api.ts';

const GLOBAL = 'GLOBAL';
const DAY_MS = 86_400_000;
const LIFETIMES = [7, 30, 90, 180, 365];

/**
 * Mints a key: a name, an expiry within the installation's maximum lifetime, grants from the
 * permissions the user holds at the chosen scope (the server intersects them anyway, so offering
 * more would only mint keys that silently lose what was ticked), and optionally the MCP tools the
 * key may call.
 */
export function MintKeyForm({ onMinted }: Readonly<{ onMinted: (created: CreatedTokenView) => void }>) {
  const create = useCreateToken();
  const policy = useTokenPolicy();
  const tools = useMcpTools();
  const catalogue = usePermissionsCatalogue();
  const clusters = useClusters();
  const { can } = useCan();

  const [name, setName] = useState('');
  const [nameError, setNameError] = useState<string | null>(null);
  const [scope, setScope] = useState<string>(GLOBAL);
  const [chosen, setChosen] = useState<string[]>([]);
  const [lifetime, setLifetime] = useState<string | null>(null);
  const [mcpTools, setMcpTools] = useState<string[]>([]);

  const clusterId = scope === GLOBAL ? undefined : scope;
  // What the user can actually delegate at the selected scope. A wildcard grant makes every
  // catalogued permission available; `can` resolves that.
  const available = useMemo(
    () => (catalogue.data ?? []).filter((p) => can(p.action, clusterId) && (scope === GLOBAL || !p.globalOnly)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [catalogue.data, clusterId, scope],
  );

  const latest = policy.data ? Date.parse(policy.data.latestExpiry) : null;
  const maxDays = latest === null ? null : Math.round((latest - serverNow()) / DAY_MS);
  const lifetimeOptions = maxDays === null ? [] : LIFETIMES.filter((d) => d <= maxDays).map(String);
  if (maxDays !== null && maxDays >= 1 && !lifetimeOptions.includes(String(maxDays))) {
    lifetimeOptions.push(String(maxDays));
  }
  const selectedLifetime = lifetime ?? (lifetimeOptions.includes('30') ? '30' : (lifetimeOptions.at(-1) ?? null));

  const submit = () => {
    if (!name.trim()) {
      setNameError('Name the key after where it will be used.');
      return;
    }
    if (!selectedLifetime) {
      return;
    }
    const grants: TokenGrantRequest[] = chosen.map((action) => ({
      action,
      scopeType: scope === GLOBAL ? GLOBAL : 'CLUSTER',
      scopeId: scope === GLOBAL ? null : scope,
    }));
    // Never past the cap the server stated, however long the form stayed open.
    const expiresAt = new Date(
      Math.min(serverNow() + Number(selectedLifetime) * DAY_MS, latest ?? Infinity),
    ).toISOString();
    create.mutate({ name: name.trim(), expiresAt, grants, mcpTools }, { onSuccess: onMinted });
  };

  return (
    <Stack gap="sm">
      <TextInput
        label="Name"
        value={name}
        onChange={(e) => setName(e.currentTarget.value)}
        onBlur={() => setNameError(name.trim() ? null : 'Name the key after where it will be used.')}
        error={nameError}
        required
      />
      <Select
        label="Expires in"
        description={
          policy.data
            ? `Keys on this installation live at most ${maxDays} days.`
            : 'Loading the installation’s maximum lifetime…'
        }
        value={selectedLifetime}
        onChange={setLifetime}
        data={lifetimeOptions.map((d) => ({ value: d, label: `${d} days` }))}
        allowDeselect={false}
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
        allowDeselect={false}
        data={[
          { value: GLOBAL, label: 'Global — every cluster' },
          ...(clusters.data ?? []).map((c) => ({ value: c.id, label: c.name })),
        ]}
      />
      <div>
        <Text size="sm" fw={500}>
          Permissions
        </Text>
        <Text size="xs" c="dimmed" mb="xs">
          Only what you hold at this scope is offered.
        </Text>
        {available.length === 0 ? (
          <Text size="xs" c="dimmed">
            You hold nothing at this scope, so a key made here could do nothing.
          </Text>
        ) : (
          <PermissionPicker catalogue={available} value={chosen} onChange={setChosen} />
        )}
      </div>
      {tools.data ? (
        <MultiSelect
          label="MCP tools"
          description="Leave empty to let the key call every tool its permissions allow. Other tools stay hidden from it."
          placeholder={mcpTools.length === 0 ? 'Every tool' : undefined}
          searchable
          value={mcpTools}
          onChange={setMcpTools}
          data={tools.data.map((t) => ({
            value: t.name,
            label: `${t.name} (${t.posture === 'READ' ? 'read' : 'changes state'})`,
          }))}
        />
      ) : null}
      {create.isError ? (
        <Alert color="red" title="The key was not created">
          {createFailure(create.error)}
        </Alert>
      ) : null}
      {chosen.length === 0 ? (
        <Text size="xs" c="dimmed">
          Choose at least one permission; a key without any could sign in and do nothing.
        </Text>
      ) : null}
      <Button loading={create.isPending} disabled={chosen.length === 0 || !selectedLifetime} onClick={submit}>
        Create
      </Button>
    </Stack>
  );
}

/** Why a key could not be made, and what to do about it. */
function createFailure(error: { type: string; message: string }): string {
  if (error.type.endsWith('/session-required')) {
    return 'A key can only be created from a signed-in console session, not with another key. Sign in to the console and create it there.';
  }
  if (error.type.endsWith('/mfa-required')) {
    return 'Your role requires two-step verification, and this session has not completed it. Sign out, sign in with your second factor, then create the key.';
  }
  return error.message;
}

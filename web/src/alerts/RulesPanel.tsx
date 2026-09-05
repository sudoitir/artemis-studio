import { useState } from 'react';
import { ActionIcon, Badge, Group, Stack, Switch, Table, Text, Title } from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';

import {
  useAlertRules,
  useCreateAlertRule,
  useDeleteAlertRule,
  useNotificationChannels,
  useUpdateAlertRule,
  type AlertRuleView,
} from '../api/client.ts';
import { RuleForm } from './RuleForm.tsx';
import { comparatorSymbol, severityTone, stateConditionLabel } from './severity.ts';

function ruleCondition(rule: AlertRuleView): string {
  if (rule.kind === 'METRIC_THRESHOLD') {
    return `${rule.metric} ${comparatorSymbol(rule.comparator ?? '')} ${rule.threshold}`;
  }
  return stateConditionLabel(rule.stateCondition ?? '');
}

/** Rule CRUD — thresholds and cluster-state conditions share one form and table (alerting spec). */
export function RulesPanel({ clusterId }: { clusterId: string }) {
  const rules = useAlertRules(clusterId);
  const channels = useNotificationChannels();
  const create = useCreateAlertRule(clusterId);
  const update = useUpdateAlertRule(clusterId);
  const remove = useDeleteAlertRule(clusterId);

  const [editing, setEditing] = useState<AlertRuleView | null>(null);
  const channelById = new Map((channels.data ?? []).map((c) => [c.id, c.name]));

  return (
    <Stack gap="md">
      <Title order={4}>{editing ? `Edit "${editing.name}"` : 'New rule'}</Title>
      <RuleForm
        key={editing?.id ?? 'new'}
        channels={channels.data ?? []}
        initial={editing ?? undefined}
        submitting={create.isPending || update.isPending}
        onCancel={editing ? () => setEditing(null) : undefined}
        onSubmit={(body) => {
          if (editing) {
            update.mutate(
              { ruleId: editing.id, body },
              {
                onSuccess: () => {
                  setEditing(null);
                  notifications.show({ message: `Updated "${body.name}"`, color: 'green' });
                },
                onError: (e) => notifications.show({ message: e.message, color: 'red' }),
              },
            );
          } else {
            create.mutate(body, {
              onSuccess: () => notifications.show({ message: `Added "${body.name}"`, color: 'green' }),
              onError: (e) => notifications.show({ message: e.message, color: 'red' }),
            });
          }
        }}
      />

      {rules.isPending ? (
        <Text size="sm" c="dimmed">
          Loading…
        </Text>
      ) : (rules.data ?? []).length === 0 ? (
        <Text size="sm" c="dimmed">
          No rules yet — add one above, or edit the built-in split-brain / node-down /
          replication-behind rules seeded when this cluster was registered.
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name</Table.Th>
              <Table.Th>Condition</Table.Th>
              <Table.Th>For</Table.Th>
              <Table.Th>Severity</Table.Th>
              <Table.Th>Channels</Table.Th>
              <Table.Th>Enabled</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(rules.data ?? []).map((r) => {
              const tone = severityTone(r.severity);
              return (
                <Table.Tr key={r.id}>
                  <Table.Td>
                    <Text size="sm">{r.name}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" ff="monospace">
                      {ruleCondition(r)}
                    </Text>
                  </Table.Td>
                  <Table.Td>{r.forSeconds}s</Table.Td>
                  <Table.Td>
                    <Badge size="xs" variant="light" color={tone.color}>
                      {tone.word}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      {r.channelIds.length
                        ? r.channelIds.map((id) => channelById.get(id) ?? id).join(', ')
                        : 'none'}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Switch
                      checked={r.enabled}
                      onChange={() =>
                        update.mutate({
                          ruleId: r.id,
                          body: {
                            name: r.name,
                            kind: r.kind,
                            metric: r.metric ?? undefined,
                            comparator: r.comparator ?? undefined,
                            threshold: r.threshold ?? undefined,
                            stateCondition: r.stateCondition ?? undefined,
                            forSeconds: r.forSeconds,
                            severity: r.severity,
                            enabled: !r.enabled,
                            channelIds: r.channelIds,
                          },
                        })
                      }
                      size="sm"
                      aria-label={`${r.enabled ? 'Disable' : 'Enable'} ${r.name}`}
                    />
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      <ActionIcon
                        variant="subtle"
                        onClick={() => setEditing(r)}
                        aria-label={`Edit ${r.name}`}
                      >
                        <IconPencil size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        color="red"
                        onClick={() => remove.mutate(r.id)}
                        aria-label={`Delete ${r.name}`}
                      >
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

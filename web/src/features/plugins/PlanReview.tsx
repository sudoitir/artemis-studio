import { Alert, Anchor, Collapse, Group, List, Stack, Text, Title } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useDisclosure } from '@mantine/hooks';

import type { PluginPlanView, PluginViolationView } from './api.ts';
import styles from './Plugins.module.css';
import { count, downtime } from './words.ts';

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Stack gap={4}>
      <Title order={4} fz="sm">
        {title}
      </Title>
      {children}
    </Stack>
  );
}

/**
 * "What this plugin will be able to do" (design.md §8): every capability as a sentence, what an
 * update adds or takes away, and what confirming will interrupt — all before anything is armed.
 */
export function PlanReview({ plan, warnings = [] }: { plan: PluginPlanView; warnings?: PluginViolationView[] }) {
  const info = plan.info;
  const c = info.contributions;
  const d = plan.diff;
  const [sqlOpen, sql] = useDisclosure(false);
  const changes = plan.pendingChangesets.length;
  const readTools = c.mcpTools.filter((t) => t.posture === 'read');
  const writeTools = c.mcpTools.filter((t) => t.posture !== 'read');
  const update = !!plan.fromVersion && plan.fromVersion !== plan.toVersion;
  const rolesLosing = Object.entries(plan.rolesLosingPermission).filter(([, roles]) => roles > 0);

  return (
    <Stack gap="md">
      <Stack gap={2}>
        <Text fw={600}>
          {info.title} {plan.toVersion}
          {update ? (
            <Text component="span" c="dimmed">
              {' '}
              (installed: {plan.fromVersion})
            </Text>
          ) : null}
        </Text>
        <Text size="sm" c="dimmed">
          by {info.vendor.name}
          {info.license ? ` · ${info.license}` : ''} · supports Studio {info.since}
          {info.until ? ` to ${info.until}` : ' and later'}
        </Text>
        {info.description ? <Text size="sm">{info.description}</Text> : null}
      </Stack>

      {plan.missingRequires.length > 0 ? (
        <Alert variant="light" color="red" title="It cannot be activated yet">
          It requires {plan.missingRequires.join(', ')}, which {plan.missingRequires.length === 1 ? 'is' : 'are'} not
          active. Install or enable {plan.missingRequires.length === 1 ? 'it' : 'them'} first.
        </Alert>
      ) : null}

      <Section title="What this plugin will be able to do">
        <List size="sm" spacing={2}>
          {c.ui ? <List.Item>Add screens to Studio, which act with the rights of whoever views them.</List.Item> : null}
          <List.Item>Run code inside Studio, with Studio's access to your brokers and database.</List.Item>
          {readTools.length > 0 ? (
            <List.Item>
              Give the assistant {count(readTools.length, 'read-only tool')}: {readTools.map((t) => t.name).join(', ')}.
            </List.Item>
          ) : null}
          {writeTools.length > 0 ? (
            <List.Item>
              Give the assistant {count(writeTools.length, 'tool')} that change things: {writeTools.map((t) => t.name).join(', ')}.
            </List.Item>
          ) : null}
          {c.permissions.length > 0 ? (
            <List.Item>
              Define {count(c.permissions.length, 'permission')} you can grant through roles:{' '}
              {c.permissions.map((p) => (p.description ? `${p.action} (${p.description})` : p.action)).join(', ')}.
            </List.Item>
          ) : null}
          {c.settingKeys.length > 0 ? <List.Item>Keep {count(c.settingKeys.length, 'setting')} of its own.</List.Item> : null}
          {c.streamTopics.length > 0 ? <List.Item>Send live updates to open screens.</List.Item> : null}
          {changes > 0 ? (
            <List.Item>
              {plan.fromVersion ? 'Change its own database schema' : 'Create its own database schema'} with{' '}
              {count(changes, 'change')}.
            </List.Item>
          ) : null}
          {info.requires.length > 0 ? <List.Item>Depend on {info.requires.join(', ')}.</List.Item> : null}
        </List>
      </Section>

      {update ? (
        <Section title="What changes">
          <List size="sm" spacing={2}>
            {[...d.permissionsAdded.map((p) => `Adds permission ${p}`),
              ...d.permissionsRemoved.map((p) => `Removes permission ${p}`),
              ...d.mcpToolsAdded.map((t) => `Adds assistant tool ${t}`),
              ...d.mcpToolsRemoved.map((t) => `Removes assistant tool ${t}`),
              ...d.settingKeysAdded.map((k) => `Adds setting ${k}`),
              ...d.settingKeysRemoved.map((k) => `Removes setting ${k}`),
              ...d.streamTopicsAdded.map((t) => `Adds live topic ${t}`),
              ...d.streamTopicsRemoved.map((t) => `Removes live topic ${t}`)].map((line) => (
              <List.Item key={line}>{line}</List.Item>
            ))}
            {rolesLosing.map(([permission, roles]) => (
              <List.Item key={`roles-${permission}`}>
                <span className={styles.warning}>
                  {count(roles, 'role')} {roles === 1 ? 'grants' : 'grant'} {permission}, which this version removes; those
                  roles lose it.
                </span>
              </List.Item>
            ))}
          </List>
          {info.changeNotes ? (
            <Text size="sm" className={styles.notes}>
              {info.changeNotes}
            </Text>
          ) : null}
        </Section>
      ) : null}

      <Section title="What happens when you confirm">
        <Text size="sm">{downtime(plan)}</Text>
        {changes > 0 ? (
          <>
            {!plan.reversible ? (
              <Text size="sm" className={styles.warning}>
                Irreversible: {count(plan.pendingChangesets.filter((cs) => !cs.reversible).length, 'database change')} cannot be
                undone, so Roll back will not be available afterwards. Take a database backup first.
              </Text>
            ) : null}
            <Group gap="xs">
              <Anchor component="button" type="button" size="sm" onClick={sql.toggle} aria-expanded={sqlOpen}>
                {sqlOpen ? 'Hide the SQL' : 'Show the SQL'}
              </Anchor>
            </Group>
            <Collapse expanded={sqlOpen}>
              <CodeHighlight code={plan.updateSql.trimEnd()} language="sql" />
            </Collapse>
          </>
        ) : null}
      </Section>

      {warnings.length > 0 ? (
        <Section title="Worth knowing">
          <List size="sm" spacing={2}>
            {warnings.map((w) => (
              <List.Item key={w.code + w.message}>{w.message}</List.Item>
            ))}
          </List>
        </Section>
      ) : null}
    </Stack>
  );
}

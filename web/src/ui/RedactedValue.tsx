import { Alert, Badge, Group, Stack, Text } from '@mantine/core';
import { IconEye, IconLock, IconShieldOff } from '@tabler/icons-react';

import type { components } from '../kernel/api/schema.d.ts';

type RedactionView = components['schemas']['RedactionView'];
type WithheldView = components['schemas']['WithheldView'];

/**
 * How a governed value says what it is (operator-ui spec): a masked value, a dropped credential and a value
 * shown in clear by grant are each named in words, with an icon, so colour never carries the meaning. The
 * view stays near-monochrome — none of these states is an error.
 */
function markLabel(r: RedactionView): string {
  if (r.clear) return `Sensitive: ${r.label}, shown by your access`;
  return r.action === 'DROP' ? `Dropped: ${r.label}` : `Masked: ${r.label}`;
}

/** One mark per distinct kind of redaction, with a count when a value holds several of the same. */
export function RedactionMarks({ redactions }: { redactions: RedactionView[] }) {
  if (redactions.length === 0) return null;
  const counts = new Map<string, { label: string; clear: boolean; count: number }>();
  for (const r of redactions) {
    const label = markLabel(r);
    const entry = counts.get(label);
    if (entry) entry.count += 1;
    else counts.set(label, { label, clear: r.clear, count: 1 });
  }
  return (
    <Group gap={4} wrap="wrap">
      {[...counts.values()].map(({ label, clear, count }) => (
        <Badge
          key={label}
          size="xs"
          variant="outline"
          color="gray"
          tt="none"
          leftSection={clear ? <IconEye size={11} aria-hidden /> : <IconLock size={11} aria-hidden />}
        >
          {count > 1 ? `${label} (${count})` : label}
        </Badge>
      ))}
    </Group>
  );
}

/** A header or property value with the marks for its path. A masked value is shown as its marker text. */
export function GovernedValue({ value, redactions }: { value: unknown; redactions: RedactionView[] }) {
  return (
    <Stack gap={2}>
      <Text size="xs" ff="monospace" style={{ wordBreak: 'break-all' }}>
        {String(value)}
      </Text>
      <RedactionMarks redactions={redactions} />
    </Stack>
  );
}

/** Content that was not shown, with the reason and — where one exists — the setting that changes it. */
export function WithheldNotice({ withheld }: { withheld: WithheldView[] }) {
  if (withheld.length === 0) return null;
  return (
    <Stack gap="xs">
      {withheld.map((w) => (
        <Alert
          key={`${w.location}-${w.reason}`}
          variant="outline"
          color="gray"
          icon={<IconShieldOff size={16} aria-hidden />}
          title={w.location === 'BODY' ? 'Body withheld' : 'Content withheld'}
        >
          <Text size="sm">{w.reason}</Text>
          {w.settingKey ? (
            <Text size="xs" c="dimmed">
              Changed by the setting <code>{w.settingKey}</code>. Users with clear access see the whole content.
            </Text>
          ) : (
            <Text size="xs" c="dimmed">
              Users with clear access see the whole content.
            </Text>
          )}
        </Alert>
      ))}
    </Stack>
  );
}

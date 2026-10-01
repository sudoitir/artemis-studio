import { Group, Stack, Text } from '@mantine/core';
import { IconEye, IconLock, IconShieldOff } from '@tabler/icons-react';

import type { components } from '../kernel/api/schema.d.ts';
import classes from './RedactedValue.module.css';

type RedactionView = components['schemas']['RedactionView'];
type WithheldView = components['schemas']['WithheldView'];

/**
 * How a governed value says what it is (operator-ui spec): a masked value, a dropped credential and a value
 * shown in clear by grant are each named in words, with an icon, so colour never carries the meaning. The
 * view stays near-monochrome — none of these states is an error.
 *
 * <p>The value itself is only ever the text content of {@link GovernedValue}: the marks, the headings and
 * every attribute (`title`, `aria-*`) are built from the redaction's label alone, so a masked value cannot
 * reach a tooltip, an accessible name or what a copy picks up from them.
 */
function markLabel(r: RedactionView): string {
  if (r.clear) return `Sensitive: ${r.label}, shown by your access`;
  return r.action === 'DROP' ? `Dropped: ${r.label}` : `Masked: ${r.label}`;
}

/** One mark per distinct kind of redaction, with a count when a value holds several of the same. */
export function RedactionMarks({ redactions }: Readonly<{ redactions: RedactionView[] }>) {
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
        <span key={label} className={classes.mark}>
          {clear ? <IconEye size="0.75rem" aria-hidden /> : <IconLock size="0.75rem" aria-hidden />}
          {count > 1 ? `${label} (${count})` : label}
        </span>
      ))}
    </Group>
  );
}

/** A header or property value with the marks for its path. A masked value is shown as its marker text. */
export function GovernedValue({ value, redactions }: Readonly<{ value: unknown; redactions: RedactionView[] }>) {
  return (
    <Stack gap={2}>
      <Text size="xs" ff="monospace" className={classes.value}>
        {String(value)}
      </Text>
      <RedactionMarks redactions={redactions} />
    </Stack>
  );
}

/** Content that was not shown, with the reason and — where one exists — the setting that changes it. */
export function WithheldNotice({ withheld }: Readonly<{ withheld: WithheldView[] }>) {
  if (withheld.length === 0) return null;
  return (
    <Stack gap="xs">
      {withheld.map((w) => (
        <div key={`${w.location}-${w.reason}`} className={classes.withheld}>
          <Text size="sm" className={classes.withheldTitle}>
            <IconShieldOff size="1rem" aria-hidden />
            {w.location === 'BODY' ? 'Body withheld' : 'Content withheld'}
          </Text>
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
        </div>
      ))}
    </Stack>
  );
}

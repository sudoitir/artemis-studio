import type { ReactNode } from 'react';
import { Button, Highlight, NumberInput, Switch, Text, TextInput } from '@mantine/core';
import { IconArrowBackUp, IconRestore } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { CancelRequest } from '../../kernel/approvals/CancelRequest.tsx';
import { Ago } from '../../kernel/time/Ago.tsx';
import { useServerNow } from '../../kernel/time/time.ts';
import { HeldRequestLink } from '../../ui/HeldRequestLink.tsx';
import { SETTINGS_KEY, type PendingChange, type Setting } from './api.ts';
import { useSettingsDraft } from './draftContext.ts';
import { boundsText } from './model.ts';
import classes from './Settings.module.css';

/** How each kind is written, said once beneath the setting's own hint. */
const FORMAT: Record<string, string> = {
  DURATION: 'A duration, such as 30s, 5m or 72h.',
  DURATION_OR_OFF: 'A duration, such as 30s or 5m; 0 turns it off.',
  CRON: 'Six fields: second, minute, hour, day of month, month, day of week. For example: 0 0 3 * * *',
};

/**
 * A search match: the selection tint, and an underline in the accent so it shows without the tint, while the
 * text keeps its own colour and contrast in both schemes.
 */
const MATCH = {
  backgroundColor: 'var(--as-selected)',
  color: 'inherit',
  textDecoration: 'underline 2px var(--as-accent)',
  textUnderlineOffset: '0.2em',
  padding: 0,
} as const;

/** How often the age of a pending change is brought up to date. */
const AGE_TICK_MS = 30_000;

/**
 * One setting as a form row: its input, matched to its kind, beside what state it is in (edited, modified
 * from its default, or about to be reset) and the control that takes it back. A change waiting for approval
 * is listed under it, with who asked and when.
 */
export function SettingRow({
  settingKey,
  terms,
  canWrite,
  username,
}: Readonly<{ settingKey: string; terms: string[]; canWrite: boolean; username: string | undefined }>) {
  const draft = useSettingsDraft();
  const setting = draft.settings[settingKey];
  const entry = draft.field(settingKey);
  if (!setting || !entry) return null;
  const { field, path } = entry;

  const changed = draft.changes.some((c) => c.key === settingKey);
  const label = (
    <Highlight component="span" inherit highlight={terms} highlightStyles={MATCH}>
      {setting.label}
    </Highlight>
  );
  const format = [FORMAT[setting.kind], boundsText(setting)].filter(Boolean).join(' ');
  const description = (
    <>
      <Highlight component="span" inherit highlight={terms} highlightStyles={MATCH}>
        {setting.hint}
      </Highlight>
      {format ? <span className={classes.format}>{format}</span> : null}
    </>
  );
  const common = {
    label,
    description,
    error: draft.errorOf(settingKey),
    disabled: !canWrite,
    'data-path': path,
    onBlur: () => draft.blur(settingKey),
  };

  let input: ReactNode;
  if (setting.kind === 'BOOLEAN') {
    input = (
      <Switch
        {...common}
        checked={field.value === 'true'}
        onChange={(event) => draft.edit(settingKey, String(event.currentTarget.checked))}
      />
    );
  } else if (setting.kind === 'INT') {
    input = (
      <NumberInput
        {...common}
        className={classes.input}
        value={field.value}
        onChange={(value) => draft.edit(settingKey, String(value))}
        min={setting.min == null ? undefined : Number(setting.min)}
        max={setting.max == null ? undefined : Number(setting.max)}
        allowDecimal={false}
        // Out of range is the server's to say, beside the field; clamping on blur would hide it.
        clampBehavior="none"
        inputMode="numeric"
      />
    );
  } else {
    input = (
      <TextInput
        {...common}
        className={classes.input}
        value={field.value}
        onChange={(event) => draft.edit(settingKey, event.currentTarget.value)}
        spellCheck={false}
        autoComplete="off"
      />
    );
  }

  return (
    <div className={classes.setting} data-changed={changed || undefined}>
      <div className={classes.settingInput}>{input}</div>
      <div className={classes.settingState}>
        <SettingState
          setting={setting}
          staged={field.reset}
          changed={changed}
          canWrite={canWrite}
          onReset={() => draft.stageReset(settingKey)}
          onUndo={() => draft.undo(settingKey)}
        />
        <Text size="xs" c="dimmed" className={classes.key}>
          <Highlight component="span" inherit highlight={terms} highlightStyles={MATCH}>
            {settingKey}
          </Highlight>
        </Text>
      </div>
      {setting.pending.length > 0 ? (
        <ul className={classes.pending} aria-label={`Changes to ${setting.label} waiting for approval`}>
          {setting.pending.map((pending) => (
            <PendingLine
              key={pending.heldId}
              pending={pending}
              setting={setting}
              mine={pending.requester === username}
            />
          ))}
        </ul>
      ) : null}
    </div>
  );
}

/** Where the setting stands against its default and the draft, and the control that takes it back. */
function SettingState({
  setting,
  staged,
  changed,
  canWrite,
  onReset,
  onUndo,
}: Readonly<{
  setting: Setting;
  staged: boolean;
  changed: boolean;
  canWrite: boolean;
  onReset: () => void;
  onUndo: () => void;
}>) {
  if (changed) {
    return (
      <>
        <Text size="xs" fw={600}>
          {staged ? 'Resets to its default' : `Edited · was ${setting.value}`}
        </Text>
        <Button
          size="compact-xs"
          variant="subtle"
          leftSection={<IconArrowBackUp size={14} aria-hidden />}
          onClick={onUndo}
          aria-label={`Undo the change to ${setting.label}`}
        >
          Undo
        </Button>
      </>
    );
  }
  if (setting.overridden) {
    return (
      <>
        <Text size="xs">Modified · default {setting.defaultValue}</Text>
        <Button
          size="compact-xs"
          variant="subtle"
          leftSection={<IconRestore size={14} aria-hidden />}
          onClick={onReset}
          disabled={!canWrite}
          aria-label={`Reset ${setting.label} to its default, ${setting.defaultValue}`}
        >
          Reset to default
        </Button>
      </>
    );
  }
  return (
    <Text size="xs" c="dimmed">
      Default
    </Text>
  );
}

/** A change to this setting waiting for a second person: what it sets, who asked, when, and the way to it. */
function PendingLine({
  pending,
  setting,
  mine,
}: Readonly<{ pending: PendingChange; setting: Setting; mine: boolean }>) {
  const now = useServerNow(AGE_TICK_MS);
  const qc = useQueryClient();
  const to = pending.reset ? `its default, ${setting.defaultValue}` : (pending.value ?? '');
  return (
    <li className={classes.pendingLine}>
      <Text size="sm" component="span">
        <Text span fw={600}>
          Pending approval
        </Text>{' '}
        → {to} · {pending.requester} · <Ago at={pending.requestedAt} now={now} />
      </Text>
      <HeldRequestLink id={pending.heldId} />
      {mine ? (
        <CancelRequest
          id={pending.heldId}
          summary={`Change ${setting.label} to ${to}`}
          size="xs"
          onCancelled={() => void qc.invalidateQueries({ queryKey: SETTINGS_KEY })}
        />
      ) : null}
    </li>
  );
}

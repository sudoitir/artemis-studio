import { useMemo, useRef, useState } from 'react';
import { Button, Stack, Switch, Text, TextInput } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { useResetSetting, useSettings, useUpdateSetting, type SettingsResponse } from './api.ts';
import classes from './Settings.module.css';

const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };
const RESET: ActionVerb = { verb: 'Reset', past: 'Reset', progressive: 'Resetting' };

const WRITE_REASON = 'Changing settings needs the settings:write permission.';

type Setting = SettingsResponse['settings'][string];

/**
 * The settings form is generated from the API, not from a list kept here. Every
 * key carries its own group, label, hint and kind (ADR-0047), so adding a setting
 * on the server adds it to this screen with no frontend change — and, more to the
 * point, a hint can never drift out of date with the behaviour it describes,
 * which is exactly what happened to the previous hardcoded list.
 */
export function OperationalConfig() {
  const settings = useSettings();
  const update = useUpdateSetting();
  const reset = useResetSetting();
  const { can, loading } = useCan();
  // While grants load, offer the control; the server is the enforcement point.
  const canWrite = loading || can('settings:write');

  // Group in first-seen order: the server sends the registry order on purpose.
  const groups = useMemo(() => {
    const out: { name: string; keys: string[] }[] = [];
    for (const [key, value] of Object.entries(settings.data?.settings ?? {})) {
      const existing = out.find((g) => g.name === value.group);
      if (existing) existing.keys.push(key);
      else out.push({ name: value.group, keys: [key] });
    }
    return out;
  }, [settings.data]);

  if (settings.isError) {
    return <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />;
  }

  // The frame holds the height of a few groups, so the form that replaces it does not push anything.
  if (settings.isPending) {
    return <LoadingState label="Loading settings" blockSize="24rem" />;
  }

  const save = (key: string, current: Setting, next: string) =>
    update.mutate(
      { key, value: next },
      {
        onSuccess: () => notify.succeeded({ action: SAVE, subject: current.label }),
        onError: (error) =>
          notify.failed({
            action: SAVE,
            subject: current.label,
            cause: error.message,
            next: `It is still ${current.value}. Try again.`,
          }),
      },
    );

  const clear = (key: string, current: Setting) =>
    reset.mutate(key, {
      onSuccess: () => notify.succeeded({ action: RESET, subject: current.label }),
      onError: (error) =>
        notify.failed({
          action: RESET,
          subject: current.label,
          cause: error.message,
          next: `It is still ${current.value}. Try again.`,
        }),
    });

  return (
    <Stack gap="lg">
      {canWrite ? null : <Text size="sm">{WRITE_REASON}</Text>}
      {groups.map((group) => (
        <Section key={group.name} title={group.name} headingLevel={3}>
          <Stack gap="md" className={classes.narrow}>
            {group.keys.map((key) => {
              const current = settings.data.settings[key];
              if (!current) return null;
              return (
                <SettingField
                  // A new value from the server starts the field over from it.
                  key={`${key}:${current.value}`}
                  setting={current}
                  canWrite={canWrite}
                  saving={update.isPending && update.variables?.key === key}
                  resetting={reset.isPending && reset.variables === key}
                  onSave={(next) => save(key, current, next)}
                  onReset={() => clear(key, current)}
                />
              );
            })}
          </Stack>
        </Section>
      ))}
    </Stack>
  );
}

const NOT_A_NUMBER = 'Enter a whole number.';
const UNCHANGED = 'Nothing to save: the value is unchanged.';

/** One setting: a switch that saves as it is flipped, or a field with its own Save. */
function SettingField({
  setting: current,
  canWrite,
  saving,
  resetting,
  onSave,
  onReset,
}: Readonly<{
  setting: Setting;
  canWrite: boolean;
  saving: boolean;
  resetting: boolean;
  onSave: (value: string) => void;
  onReset: () => void;
}>) {
  const [value, setValue] = useState(current.value);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const resetControl = current.overridden ? (
    <Button size="xs" variant="subtle" disabled={!canWrite} loading={resetting} onClick={onReset}>
      Reset
    </Button>
  ) : null;

  if (current.kind === 'BOOLEAN') {
    // A switch saves as it is flipped: there is no half-typed value to hold back.
    return (
      <div className={classes.setting}>
        <Switch
          label={current.label}
          description={current.hint}
          checked={current.value === 'true'}
          disabled={!canWrite || saving}
          onChange={(e) => onSave(String(e.currentTarget.checked))}
          size="sm"
        />
        {resetControl}
      </div>
    );
  }

  const invalid = (v: string) => (current.kind === 'INT' && !/^-?\d+$/.test(v.trim()) ? NOT_A_NUMBER : null);

  const submit = () => {
    const problem = invalid(value) ?? (value === current.value ? UNCHANGED : null);
    setError(problem);
    if (problem) {
      input.current?.focus();
      return;
    }
    onSave(value);
  };

  return (
    <div>
      <div className={classes.setting}>
        <TextInput
          ref={input}
          className={classes.settingField}
          label={current.label}
          description={current.hint}
          value={value}
          inputMode={current.kind === 'INT' ? 'numeric' : 'text'}
          disabled={!canWrite}
          error={error}
          onChange={(e) => {
            setValue(e.currentTarget.value);
            setError(null);
          }}
          onBlur={() => setError(invalid(value))}
          size="xs"
        />
        <Button size="xs" disabled={!canWrite} loading={saving} onClick={submit}>
          Save
        </Button>
        {resetControl}
      </div>
      {current.overridden ? (
        <Text size="xs" c="dimmed">
          overridden — default is {current.defaultValue}
        </Text>
      ) : null}
    </div>
  );
}

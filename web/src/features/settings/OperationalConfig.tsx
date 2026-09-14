import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Group, Loader, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { useResetSetting, useSettings, useUpdateSetting } from './api.ts';

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
  const [draft, setDraft] = useState<Record<string, string>>({});

  const entries = useMemo(
    () => Object.entries(settings.data?.settings ?? {}),
    [settings.data],
  );

  // Group in first-seen order: the server sends the registry order on purpose.
  const groups = useMemo(() => {
    const out: { name: string; keys: string[] }[] = [];
    for (const [key, value] of entries) {
      const existing = out.find((g) => g.name === value.group);
      if (existing) existing.keys.push(key);
      else out.push({ name: value.group, keys: [key] });
    }
    return out;
  }, [entries]);

  useEffect(() => {
    if (settings.data) {
      setDraft(Object.fromEntries(entries.map(([k, v]) => [k, v.value])));
    }
  }, [settings.data, entries]);

  if (settings.isError) {
    return (
      <Alert color="red" variant="light" title={settings.error.title}>
        {settings.error.message}
      </Alert>
    );
  }

  if (settings.isPending) {
    return <Loader size="sm" />;
  }

  return (
    <Stack gap="lg">
      {groups.map((group) => (
        <Stack key={group.name} gap="sm" maw={560}>
          <Text size="sm" fw={600}>
            {group.name}
          </Text>
          {group.keys.map((key) => {
            const current = settings.data?.settings[key];
            if (!current) return null;
            const value = draft[key] ?? '';
            const dirty = value !== current.value;
            return (
              <div key={key}>
                <Group align="flex-end" gap="xs">
                  <TextInput
                    label={current.label}
                    description={current.hint}
                    value={value}
                    inputMode={current.kind === 'INT' ? 'numeric' : 'text'}
                    onChange={(e) => {
                      const v = e.currentTarget.value;
                      setDraft((d) => ({ ...d, [key]: v }));
                    }}
                    w={300}
                    size="xs"
                  />
                  <Button
                    size="xs"
                    disabled={!dirty}
                    loading={update.isPending}
                    onClick={() =>
                      update.mutate(
                        { key, value },
                        {
                          onSuccess: () =>
                            notifications.show({ message: `${current.label} saved` }),
                          onError: (err) =>
                            notifications.show({ color: 'red', message: err.message }),
                        },
                      )
                    }
                  >
                    Save
                  </Button>
                  {current.overridden ? (
                    <Button size="xs" variant="subtle" onClick={() => reset.mutate(key)}>
                      Reset
                    </Button>
                  ) : null}
                </Group>
                {current.overridden ? (
                  <Text size="xs" c="dimmed">
                    overridden — default is {current.defaultValue}
                  </Text>
                ) : null}
              </div>
            );
          })}
        </Stack>
      ))}
    </Stack>
  );
}

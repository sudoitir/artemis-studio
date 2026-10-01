import { useState } from 'react';
import { Button, Group, Popover, Select, Stack, Switch, TagsInput, Text } from '@mantine/core';
import { useForm } from '@mantine/form';

import { useConfigureBrokerConfig, type ConfigDeclarationView } from './api.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { applyModeWords } from './words.ts';

const SAVE_MODE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };

/**
 * How the cluster's configuration is applied, stated in words in the header with
 * an inline control to change it (ADR-0067 D2). Both modes are always visible;
 * the mode decides which action is primary and which is disabled with a reason.
 *
 * <p>Without the permission the fields and Save are disabled, and the reason is stated beside them:
 * a read-only operator is told why instead of learning from a refused request.
 */
export function ModeControl({
  declaration,
  canWrite,
}: Readonly<{ declaration: ConfigDeclarationView; canWrite: boolean }>) {
  const [open, setOpen] = useState(false);
  const configure = useConfigureBrokerConfig(declaration.clusterId);
  const seed = () => ({
    applyMode: declaration.applyMode,
    reportUndeclared: declaration.reportUndeclared,
    undeclaredExclusions: declaration.undeclaredExclusions,
  });
  const form = useForm({ initialValues: seed(), validateInputOnBlur: true });
  const { applyMode, reportUndeclared } = form.values;

  const submit = form.onSubmit((values) => {
    configure.mutate(values, {
      onSuccess: () => {
        notify.succeeded({ action: SAVE_MODE, subject: `the apply mode: ${applyModeWords(values.applyMode)}` });
        setOpen(false);
      },
    });
  });

  return (
    <Popover
      opened={open}
      onChange={setOpen}
      width="22.5rem"
      position="bottom-start"
      withArrow
      shadow="md"
      trapFocus
      returnFocus
    >
      <Popover.Target>
        <Button
          variant="subtle"
          size="compact-sm"
          onClick={() => {
            form.setValues(seed());
            configure.reset();
            setOpen((o) => !o);
          }}
          aria-label={`Apply mode: ${applyModeWords(declaration.applyMode)}. Change`}
        >
          {applyModeWords(declaration.applyMode)}
          {declaration.reportUndeclared ? ' · reports undeclared items' : ''}
        </Button>
      </Popover.Target>
      <Popover.Dropdown>
        <form onSubmit={submit}>
          <Stack gap="sm">
            <Select
              label="Apply mode"
              data={[
                { value: 'STUDIO_MANAGED', label: 'Managed by Studio — apply over the management API' },
                { value: 'CONFIG_MANAGED', label: 'Managed outside Studio — export broker.xml' },
              ]}
              disabled={!canWrite}
              {...form.getInputProps('applyMode')}
              allowDeselect={false}
              size="xs"
            />
            <Text size="sm" c="dimmed">
              {applyMode === 'CONFIG_MANAGED'
                ? 'Apply is disabled; the primary action becomes copying the broker.xml fragment. Drift is still evaluated.'
                : 'Preview and apply writes to every live node, canary first. Drift is evaluated on a schedule.'}
            </Text>
            <Switch
              label="Report undeclared items"
              description="Settings and diverts on a node that the declaration does not mention. Off by default: most clusters have some."
              size="xs"
              disabled={!canWrite}
              {...form.getInputProps('reportUndeclared', { type: 'checkbox' })}
            />
            {reportUndeclared ? (
              <TagsInput
                label="Except matches"
                description="Match patterns not to report, one per tag."
                size="xs"
                disabled={!canWrite}
                {...form.getInputProps('undeclaredExclusions')}
              />
            ) : null}
            {configure.isError ? <ErrorState variant="inline" error={configure.error} /> : null}
            {canWrite ? null : (
              <Text size="sm" c="dimmed">
                Changing the mode needs the "Edit declared configuration" permission on this cluster.
              </Text>
            )}
            <Group justify="flex-end" gap="xs">
              <Button variant="default" size="xs" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" size="xs" loading={configure.isPending} disabled={!canWrite}>
                Save
              </Button>
            </Group>
          </Stack>
        </form>
      </Popover.Dropdown>
    </Popover>
  );
}

import { useState } from 'react';
import { Alert, Button, Group, Popover, Select, Stack, Switch, TagsInput, Text } from '@mantine/core';

import { useConfigureBrokerConfig, type ConfigDeclarationView } from '../api/client.ts';
import { applyModeWords } from './words.ts';

/**
 * How the cluster's configuration is applied, stated in words in the header with
 * an inline control to change it (ADR-0067 D2). Both modes are always visible;
 * the mode decides which action is primary and which is disabled with a reason.
 */
export function ModeControl({ declaration, canWrite }: { declaration: ConfigDeclarationView; canWrite: boolean }) {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<ConfigDeclarationView['applyMode']>(declaration.applyMode);
  const [reportUndeclared, setReportUndeclared] = useState(declaration.reportUndeclared);
  const [exclusions, setExclusions] = useState<string[]>(declaration.undeclaredExclusions);
  const configure = useConfigureBrokerConfig(declaration.clusterId);

  const submit = () =>
    configure.mutate(
      { applyMode: mode, reportUndeclared, undeclaredExclusions: exclusions },
      { onSuccess: () => setOpen(false) },
    );

  return (
    <Popover
      opened={open}
      onChange={setOpen}
      width={360}
      position="bottom-start"
      withArrow
      shadow="md"
      trapFocus
      returnFocus
    >
      <Popover.Target>
        <Button
          variant="subtle"
          size="compact-xs"
          px={4}
          onClick={() => {
            setMode(declaration.applyMode);
            setReportUndeclared(declaration.reportUndeclared);
            setExclusions(declaration.undeclaredExclusions);
            setOpen((o) => !o);
          }}
          aria-label={`Apply mode: ${applyModeWords(declaration.applyMode)}. Change`}
        >
          {applyModeWords(declaration.applyMode)}
          {declaration.reportUndeclared ? ' · reports undeclared items' : ''}
        </Button>
      </Popover.Target>
      <Popover.Dropdown>
        <Stack gap="sm">
          <Select
            label="Apply mode"
            data={[
              { value: 'STUDIO_MANAGED', label: 'Managed by Studio — apply over the management API' },
              { value: 'CONFIG_MANAGED', label: 'Managed outside Studio — export broker.xml' },
            ]}
            value={mode}
            onChange={(v) => setMode((v as ConfigDeclarationView['applyMode']) ?? 'STUDIO_MANAGED')}
            allowDeselect={false}
            size="xs"
          />
          <Text size="xs" c="dimmed">
            {mode === 'CONFIG_MANAGED'
              ? 'Apply is disabled; the primary action becomes copying the broker.xml fragment. Drift is still evaluated.'
              : 'Preview and apply writes to every live node, canary first. Drift is evaluated on a schedule.'}
          </Text>
          <Switch
            label="Report undeclared items"
            description="Settings and diverts on a node that the declaration does not mention. Off by default: most clusters have some."
            size="xs"
            checked={reportUndeclared}
            onChange={(e) => setReportUndeclared(e.currentTarget.checked)}
          />
          {reportUndeclared ? (
            <TagsInput
              label="Except matches"
              description="Match patterns not to report, one per tag."
              size="xs"
              value={exclusions}
              onChange={setExclusions}
            />
          ) : null}
          {configure.isError ? (
            <Alert color="red" variant="light" title={configure.error.title} role="alert">
              {configure.error.message}
            </Alert>
          ) : null}
          {!canWrite ? (
            <Text size="xs" c="dimmed">
              Changing the mode needs the "Edit declared configuration" permission on this cluster.
            </Text>
          ) : null}
          <Group justify="flex-end" gap="xs">
            <Button variant="default" size="xs" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button size="xs" loading={configure.isPending} onClick={submit}>
              Save
            </Button>
          </Group>
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}

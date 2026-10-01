import { useState } from 'react';
import { Alert, Button, FileButton, Group, Stack, Table, Text } from '@mantine/core';

import { ConfirmAction } from './ConfirmAction.tsx';
import {
  useRemoveLicense,
  useUploadLicense,
  type PluginInfoView,
  type PluginLicenseView,
  type PluginView,
} from './api.ts';
import styles from './Plugins.module.css';
import { LICENSE_LABEL, expiryNote, licenseAdvice } from './words.ts';

function when(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : '';
}

function Row({ label, children }: Readonly<{ label: string; children: React.ReactNode }>) {
  return (
    <Table.Tr>
      <Table.Th w={160}>{label}</Table.Th>
      <Table.Td>{children}</Table.Td>
    </Table.Tr>
  );
}

/** What is known about the license: only the rows that have something to say. */
function Facts({ license }: Readonly<{ license: PluginLicenseView }>) {
  return (
    <Table variant="vertical" withTableBorder>
      <Table.Tbody>
        <Row label="State">
          <b>{LICENSE_LABEL[license.state]}</b>
        </Row>
        {license.licensee ? <Row label="Licensed to">{license.licensee}</Row> : null}
        {license.expiresAt ? (
          <Row label="Ends">
            {new Date(license.expiresAt).toLocaleDateString()} ({expiryNote(license.expiresAt)})
          </Row>
        ) : null}
        {license.detail ? <Row label="The plugin says">{license.detail}</Row> : null}
        {license.uploadedAt ? (
          <Row label="Uploaded">
            {when(license.uploadedAt)}
            {license.uploadedBy ? ` by ${license.uploadedBy}` : ''}
          </Row>
        ) : null}
        {license.reportedAt ? <Row label="Last checked">{when(license.reportedAt)}</Row> : null}
      </Table.Tbody>
    </Table>
  );
}

/**
 * A plugin's license (ADR-0153): what the plugin made of the file, and the means to upload,
 * replace or remove it. Studio only stores the file; the plugin decides what it means. Both
 * changes ask for a fresh sign-in first, and a plugin without a license need never shows this.
 */
export function LicenseTab({
  plugin,
  info,
  license,
  canInstall,
  cannotInstall,
}: Readonly<{
  plugin: PluginView;
  info: PluginInfoView;
  license: PluginLicenseView;
  canInstall: boolean;
  cannotInstall?: string;
}>) {
  const upload = useUploadLicense();
  const remove = useRemoveLicense();
  const [file, setFile] = useState<File | null>(null);
  const [removing, setRemoving] = useState(false);
  const missing = license.state === 'MISSING';
  const done = upload.isSuccess && !file;

  return (
    <Stack gap="sm">
      {missing ? (
        <Text size="sm" fw={600}>
          {LICENSE_LABEL.MISSING}
        </Text>
      ) : null}
      <Text size="sm">{licenseAdvice(license.state, info.title, info.vendor.name)}</Text>
      {missing ? null : <Facts license={license} />}
      {done ? (
        <Text size="sm" role="status">
          License uploaded. {info.title} checks it shortly; this page updates by itself.
        </Text>
      ) : null}
      {remove.isSuccess && missing ? (
        <Text size="sm" role="status">
          License removed.
        </Text>
      ) : null}
      <Group gap="xs">
        <FileButton
          onChange={(picked) => {
            if (!picked) return;
            upload.reset();
            setFile(picked);
          }}
        >
          {(props) => (
            <Button {...props} disabled={!canInstall}>
              {missing ? 'Upload license…' : 'Replace license…'}
            </Button>
          )}
        </FileButton>
        {missing ? null : (
          <Button
            variant="outline"
            color="red"
            disabled={!canInstall}
            onClick={() => {
              remove.reset();
              setRemoving(true);
            }}
          >
            Remove license…
          </Button>
        )}
      </Group>
      {canInstall ? null : (
        <Text size="sm" c="dimmed">
          {cannotInstall ?? 'Only someone who can install plugins can change its license.'}
        </Text>
      )}
      {upload.isError && !file ? (
        <Alert color="red" variant="light" role="alert" title="The license was not uploaded">
          {upload.error.message}
        </Alert>
      ) : null}

      <ConfirmAction
        opened={!!file}
        onClose={() => setFile(null)}
        title={`${missing ? 'Upload' : 'Replace'} the license of ${info.title}`}
        confirmLabel={missing ? 'Upload license' : 'Replace license'}
        pending={upload.isPending}
        error={upload.error}
        onConfirm={() => file && upload.mutate({ id: plugin.id, file }, { onSuccess: () => setFile(null) })}
      >
        <Stack gap={4}>
          <Text size="sm">
            Stores <b className={styles.num}>{file?.name}</b> ({file ? Math.ceil(file.size / 1024) : 0} KB) as the
            license of {info.title}
            {missing ? '.' : ', replacing the current one.'} A license file is at most 64 KB. {info.title} decides
            whether it accepts the file; Studio does not read it.
          </Text>
        </Stack>
      </ConfirmAction>

      <ConfirmAction
        opened={removing}
        onClose={() => setRemoving(false)}
        title={`Remove the license of ${info.title}`}
        confirmLabel="Remove the license"
        typeToConfirm={plugin.id}
        danger
        pending={remove.isPending}
        error={remove.error}
        onConfirm={() => remove.mutate(plugin.id, { onSuccess: () => setRemoving(false) })}
      >
        <Text size="sm">
          Deletes the stored file. {info.title} is told at once and then has no license; what it does without one is its
          own rule. Upload the file again, or a new one, to restore it.
        </Text>
      </ConfirmAction>
    </Stack>
  );
}

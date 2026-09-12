import { useEffect, useState } from 'react';
import { Alert, Button, CopyButton, Drawer, Group, List, Radio, Stack, Text, Textarea } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useQuery } from '@tanstack/react-query';

import {
  fetchBrokerConfigXml,
  useAdoptBrokerConfig,
  useImportBrokerConfigXml,
  type ApiError,
  type ConfigDeclarationView,
  type ConfigDocumentView,
  type ConfigImportResultView,
} from '../api/client.ts';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';
import { mergeDocuments } from './document.ts';
import { useSaveDocument } from './useSaveDocument.ts';
import { WHY_NOT_AUTOMATIC } from './words.ts';

/** How a parsed document compares with the current one, per section, in counts. */
function sectionCounts(current: ConfigDocumentView, next: ConfigDocumentView) {
  const count = <T,>(label: string, before: T[], after: T[], keyOf: (t: T) => string) => {
    const beforeKeys = new Map(before.map((i) => [keyOf(i), JSON.stringify(i)]));
    let added = 0;
    let changed = 0;
    let unchanged = 0;
    for (const item of after) {
      const prev = beforeKeys.get(keyOf(item));
      if (prev === undefined) added += 1;
      else if (prev !== JSON.stringify(item)) changed += 1;
      else unchanged += 1;
    }
    const removed = before.filter((i) => !after.some((a) => keyOf(a) === keyOf(i))).length;
    return { label, added, changed, unchanged, removed, total: after.length };
  };
  return [
    count('Addresses', current.addresses, next.addresses, (a) => a.name),
    count('Address settings', current.addressSettings, next.addressSettings, (a) => a.match),
    count('Security settings', current.securitySettings, next.securitySettings, (a) => a.match),
    count('Diverts', current.diverts, next.diverts, (a) => a.name),
  ];
}

export function CountsList({ current, next }: { current: ConfigDocumentView; next: ConfigDocumentView }) {
  return (
    <List size="xs" spacing={2}>
      {sectionCounts(current, next).map((c) => (
        <List.Item key={c.label}>
          {c.label}: {c.total} recognised — {c.added} added, {c.changed} changed, {c.unchanged} unchanged
          {c.removed ? `, ${c.removed} no longer declared` : ''}
        </List.Item>
      ))}
    </List>
  );
}

/**
 * Paste a `broker.xml` (or a fragment) and preview what Studio recognised, what
 * it cannot carry, and what is wrong — before anything is saved. Raw XML is an
 * interchange format here, not a second editor (ADR-0067 D1): the saved thing is
 * the parsed document, and unsupported elements are listed, never dropped
 * silently.
 */
export function ImportXmlDrawer({
  declaration,
  opened,
  onClose,
  initialXml,
  initialNote,
}: {
  declaration: ConfigDeclarationView;
  opened: boolean;
  onClose: () => void;
  /** Prefilled text — a capability's broker.xml snippet handed over from the ledger. */
  initialXml?: string;
  /** What the prefilled text is, for the revision note and the drawer's lead. */
  initialNote?: string;
}) {
  const [xml, setXml] = useState('');
  const [combine, setCombine] = useState<'merge' | 'replace'>('merge');
  const [result, setResult] = useState<ConfigImportResultView | null>(null);
  const parse = useImportBrokerConfigXml(declaration.clusterId);
  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);

  useEffect(() => {
    if (opened) {
      setXml(initialXml ?? '');
      setCombine('merge');
      return;
    }
    setXml('');
    setResult(null);
    parse.reset();
    reset();
    // The mutation objects are stable; the reset belongs to the open/close edge, not to every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, initialXml]);

  const preview = () => parse.mutate(xml, { onSuccess: setResult });
  const canSave = result !== null && result.errors.length === 0;
  const merging = combine === 'merge' && declaration.declared;
  const next = result ? (merging ? mergeDocuments(declaration.document, result.document) : result.document) : null;

  return (
    <Drawer opened={opened} onClose={onClose} title="Import broker.xml" position="right" size="xl" padding="md">
      <Stack gap="md">
        {initialNote ? (
          <Alert variant="light" color="gray" title={initialNote}>
            This is the snippet the capability ledger shows. Preview it: the parts that are address or security
            settings become declared entries you can apply over the management API; anything static — a plugin, an
            acceptor — is listed under “Not applied” and still needs broker.xml.
          </Alert>
        ) : null}
        <Textarea
          label="broker.xml, or a fragment of its <core> section"
          description="Paste the whole file or only the sections you want. Nothing is saved until you choose to."
          value={xml}
          onChange={(e) => {
            setXml(e.currentTarget.value);
            setResult(null);
          }}
          autosize
          minRows={10}
          maxRows={24}
          styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)', fontSize: 12 } }}
        />

        {declaration.declared ? (
          <Radio.Group
            label="Combine with the current declaration"
            description="Merge keeps everything already declared and lets the pasted entries add to or update it; on an address setting the pasted keys win and the other declared keys stay. Replace makes the paste the whole declaration."
            value={combine}
            onChange={(v) => setCombine(v as 'merge' | 'replace')}
          >
            <Group gap="md" mt={4}>
              <Radio value="merge" label="Merge into it" />
              <Radio value="replace" label="Replace it" />
            </Group>
          </Radio.Group>
        ) : null}

        {parse.isError ? (
          <Alert color="red" variant="light" title={parse.error.title} role="alert">
            {parse.error.message}
          </Alert>
        ) : null}

        <div aria-live="polite">
          {result && next ? (
            <Stack gap="sm">
              <Stack gap={4}>
                <Text size="sm" fw={600}>
                  {merging ? 'After merging' : 'Recognised'}
                </Text>
                <CountsList current={declaration.document} next={next} />
              </Stack>

              <Stack gap={4}>
                <Text size="sm" fw={600}>
                  Not applied ({result.unsupported.length})
                </Text>
                {result.unsupported.length === 0 ? (
                  <Text size="xs" c="dimmed">
                    Every element was recognised.
                  </Text>
                ) : (
                  <List size="xs" spacing={2}>
                    {result.unsupported.map((u) => (
                      <List.Item key={u.path}>
                        <Text size="xs" component="span" ff="monospace">
                          {u.path}
                        </Text>{' '}
                        — {u.reason}
                      </List.Item>
                    ))}
                  </List>
                )}
              </Stack>

              {result.errors.length > 0 ? (
                <Alert color="red" variant="light" title={`${result.errors.length} error(s) — fix them to save`} role="alert">
                  <List size="xs" spacing={2}>
                    {result.errors.map((e, i) => (
                      <List.Item key={i}>
                        <Text size="xs" component="span" ff="monospace">
                          {e.field}
                        </Text>{' '}
                        — {e.message}
                      </List.Item>
                    ))}
                  </List>
                </Alert>
              ) : null}
            </Stack>
          ) : null}
        </div>

        {error ? (
          <Alert color="red" variant="light" title={error.title} role="alert">
            {error.message}
          </Alert>
        ) : null}

        <Group justify="flex-end" gap="xs">
          <Button variant="default" size="xs" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="default" size="xs" loading={parse.isPending} onClick={preview}>
            Preview import
          </Button>
          <Button
            size="xs"
            loading={isPending}
            onClick={() => {
              if (!canSave) {
                preview();
                return;
              }
              save(next!, initialNote ?? (merging ? 'Merged from broker.xml' : 'Imported from broker.xml'));
            }}
            title={canSave ? undefined : 'Previews first; a document with errors cannot be saved.'}
          >
            {`Save as revision ${declaration.revision + 1}`}
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

/** The declaration as a `<core>` fragment, for the config-managed path and for a copy into broker.xml. */
export function ExportXmlDrawer({
  declaration,
  opened,
  onClose,
}: {
  declaration: ConfigDeclarationView;
  opened: boolean;
  onClose: () => void;
}) {
  const xml = useQuery<string, ApiError>({
    queryKey: ['clusters', declaration.clusterId, 'config', 'xml', declaration.revision],
    queryFn: () => fetchBrokerConfigXml(declaration.clusterId),
    enabled: opened && declaration.declared,
  });
  return (
    <Drawer opened={opened} onClose={onClose} title="broker.xml fragment" position="right" size="xl" padding="md">
      <Stack gap="md">
        <Text size="xs" c="dimmed">
          Revision {declaration.revision} as the four sections of a <code>&lt;core&gt;</code> element. Deploy it
          through your own tooling; the next drift evaluation shows whether the running brokers match.
        </Text>
        {xml.isError ? (
          <Alert color="red" variant="light" title={xml.error.title}>
            {xml.error.message}
          </Alert>
        ) : null}
        {xml.data ? (
          <>
            <Group justify="flex-end">
              <CopyButton value={xml.data}>
                {({ copied, copy }) => (
                  <Button size="xs" variant="default" onClick={copy}>
                    {copied ? 'Copied' : 'Copy broker.xml fragment'}
                  </Button>
                )}
              </CopyButton>
            </Group>
            <CodeHighlight code={xml.data} language="xml" />
          </>
        ) : xml.isPending && opened ? (
          <Text size="xs" c="dimmed">
            Rendering…
          </Text>
        ) : null}
      </Stack>
    </Drawer>
  );
}

/**
 * Adopt what the live nodes are running as the declaration. Seeds every
 * address-setting match with the full echoed entry, so replace semantics do
 * not reset anything on the first apply (ADR-0067 D5). Reviewed before saving;
 * where nodes disagree, both are listed and neither is chosen for the operator.
 */
export function AdoptDrawer({
  declaration,
  opened,
  onClose,
}: {
  declaration: ConfigDeclarationView;
  opened: boolean;
  onClose: () => void;
}) {
  const adopt = useAdoptBrokerConfig(declaration.clusterId);
  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);

  useEffect(() => {
    if (opened) adopt.mutate();
    else {
      adopt.reset();
      reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened]);

  const result = adopt.data;
  const closes = result?.closes ?? [];
  const saveAdoption = (confirm?: string) =>
    result && save(result.document, 'Adopted from the running cluster', { source: 'ADOPT', confirm });
  return (
    <Drawer opened={opened} onClose={onClose} title="Adopt from cluster" position="right" size="xl" padding="md">
      <Stack gap="md">
        <Text size="xs" c="dimmed">
          Reads every live node and builds a declaration from what they run. Nothing is saved until you choose to.{' '}
          {WHY_NOT_AUTOMATIC}
        </Text>
        {adopt.isPending ? (
          <Text size="sm" aria-live="polite">
            Reading the live nodes…
          </Text>
        ) : null}
        {adopt.isError ? (
          <Alert color="red" variant="light" title={adopt.error.title} role="alert">
            {adopt.error.message}
          </Alert>
        ) : null}
        {result ? (
          <div aria-live="polite">
            <Stack gap="sm">
              <CountsList current={declaration.document} next={result.document} />
              {result.disagreements.length > 0 ? (
                <Alert color="yellow" variant="light" title="The nodes disagree">
                  <Text size="xs" mb={4}>
                    The first node's value was taken where they differ; check these before saving.
                  </Text>
                  <List size="xs" spacing={2}>
                    {result.disagreements.map((d, i) => (
                      <List.Item key={i}>{d}</List.Item>
                    ))}
                  </List>
                </Alert>
              ) : null}
              {closes.length > 0 ? (
                <Alert color="yellow" variant="light" title={`Closes ${closes.length} open drift finding${closes.length === 1 ? '' : 's'} with zero broker writes`}>
                  <Text size="xs" mb={4}>
                    Adopting declares what the cluster already runs, so these findings disappear because the
                    declaration moved — not because anything was fixed. Apply the current declaration instead if
                    the cluster is what is wrong.
                  </Text>
                  <List size="xs" spacing={2}>
                    {closes.map((c, i) => (
                      <List.Item key={i}>
                        {c.nodeName}: {c.finding.detail}
                      </List.Item>
                    ))}
                  </List>
                </Alert>
              ) : null}
              {result.notes.length > 0 ? (
                <List size="xs" spacing={2}>
                  {result.notes.map((n, i) => (
                    <List.Item key={i}>{n}</List.Item>
                  ))}
                </List>
              ) : null}
            </Stack>
          </div>
        ) : null}
        {error ? (
          <Alert color="red" variant="light" title={error.title} role="alert">
            {error.message}
          </Alert>
        ) : null}
        {closes.length > 0 ? (
          <ConfirmByTyping
            token={declaration.clusterName}
            label={`Type "${declaration.clusterName}" to record that closing these findings is intended`}
            confirmLabel={`Save as revision ${declaration.revision + 1}`}
            color="yellow"
            loading={isPending}
            disabled={!result}
            onConfirm={() => saveAdoption(declaration.clusterName)}
          />
        ) : (
          <Group justify="flex-end" gap="xs">
            <Button variant="default" size="xs" onClick={onClose}>
              Cancel
            </Button>
            <Button size="xs" loading={isPending} disabled={!result} onClick={() => saveAdoption()}>
              {`Save as revision ${declaration.revision + 1}`}
            </Button>
          </Group>
        )}
      </Stack>
    </Drawer>
  );
}

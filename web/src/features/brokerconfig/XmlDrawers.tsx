import { useEffect, useState } from 'react';
import { Button, CopyButton, Drawer, FileButton, Group, List, Radio, Stack, Text, Textarea } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';

import {
  fetchBrokerConfigXml,
  useAdoptBrokerConfig,
  useImportBrokerConfigXml,
  type ConfigDeclarationView,
  type ConfigDocumentView,
  type ConfigImportResultView,
} from './api.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { mergeDocuments } from './document.ts';
import { useSaveDocument } from './useSaveDocument.ts';
import { WHY_NOT_AUTOMATIC } from './words.ts';
import { XmlBlock } from './XmlBlock.tsx';
import classes from './Configuration.module.css';

const COPY: ActionVerb = { verb: 'Copy', past: 'Copied', progressive: 'Copying' };

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

export function CountsList({ current, next }: Readonly<{ current: ConfigDocumentView; next: ConfigDocumentView }>) {
  return (
    <List size="sm" spacing="xs">
      {sectionCounts(current, next).map((c) => (
        <List.Item key={c.label}>
          {c.label}: {c.total} recognised — {c.added} added, {c.changed} changed, {c.unchanged} unchanged
          {c.removed ? `, ${c.removed} no longer declared` : ''}
        </List.Item>
      ))}
    </List>
  );
}

/** What the pasted XML would become: counts against the current declaration, what is not carried, what is wrong. */
function ImportPreview({
  declaration,
  result,
  next,
  merging,
}: Readonly<{
  declaration: ConfigDeclarationView;
  result: ConfigImportResultView;
  next: ConfigDocumentView;
  merging: boolean;
}>) {
  return (
    <Stack gap="sm">
      <Section headingLevel={3} title={merging ? 'After merging' : 'Recognised'}>
        <CountsList current={declaration.document} next={next} />
      </Section>

      <Section headingLevel={3} title={`Not applied (${result.unsupported.length})`}>
        {result.unsupported.length === 0 ? (
          <Text size="sm" c="dimmed">
            Every element was recognised.
          </Text>
        ) : (
          <List size="sm" spacing="xs">
            {result.unsupported.map((u) => (
              <List.Item key={u.path}>
                <Text size="sm" component="span" ff="monospace">
                  {u.path}
                </Text>{' '}
                — {u.reason}
              </List.Item>
            ))}
          </List>
        )}
      </Section>

      {result.errors.length > 0 ? (
        <ErrorState
          error={
            new ApiError(422, {
              detail: `${result.errors.length} error${result.errors.length === 1 ? '' : 's'} in the XML. Nothing can be saved until they are fixed.`,
              errors: result.errors,
            })
          }
        />
      ) : null}
    </Stack>
  );
}

/** The document the import would save: the paste merged into the declaration, or the paste alone. */
function importedDocument(
  declaration: ConfigDeclarationView,
  result: ConfigImportResultView | null,
  merging: boolean,
): ConfigDocumentView | null {
  if (!result) return null;
  return merging ? mergeDocuments(declaration.document, result.document) : result.document;
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
}: Readonly<{
  declaration: ConfigDeclarationView;
  opened: boolean;
  onClose: () => void;
  /** Prefilled text — a capability's broker.xml snippet handed over from the ledger. */
  initialXml?: string;
  /** What the prefilled text is, for the revision note and the drawer's lead. */
  initialNote?: string;
}>) {
  const [xml, setXml] = useState('');
  const [combine, setCombine] = useState<'merge' | 'replace'>('merge');
  const [result, setResult] = useState<ConfigImportResultView | null>(null);
  const [loaded, setLoaded] = useState<string | null>(null);
  const parse = useImportBrokerConfigXml(declaration.clusterId);
  const { reset: resetParse } = parse;
  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);

  // The reset belongs to the open/close edge, not to every render.
  useEffect(() => {
    if (opened) {
      setXml(initialXml ?? '');
      setCombine('merge');
      return;
    }
    setXml('');
    setResult(null);
    setLoaded(null);
    resetParse();
    reset();
  }, [opened, initialXml, resetParse, reset]);

  const preview = () => parse.mutate(xml, { onSuccess: setResult });
  const load = async (file: File | null) => {
    if (!file) return;
    setXml(await file.text());
    setLoaded(file.name);
    setResult(null);
  };
  const canSave = result !== null && result.errors.length === 0;
  const merging = combine === 'merge' && declaration.declared;
  const next = importedDocument(declaration, result, merging);

  return (
    <Drawer opened={opened} onClose={onClose} title="Import broker.xml" position="right" size="xl" padding="md">
      <Stack gap="md">
        {initialNote ? (
          <Section
            headingLevel={3}
            variant="card"
            title={initialNote}
            description="This is the snippet the capability ledger shows. Preview it: the parts that are address or security settings become declared entries you can apply over the management API; anything static — a plugin, an acceptor — is listed under “Not applied” and still needs broker.xml."
          />
        ) : null}
        <Group justify="space-between" align="center" gap="xs">
          <Text size="sm" c="dimmed" aria-live="polite">
            {loaded ? `Loaded ${loaded}` : 'Paste below, or load a file.'}
          </Text>
          <FileButton
            onChange={load}
            accept=".xml,application/xml,text/xml"
            inputProps={{ 'aria-label': 'broker.xml file' }}
          >
            {(props) => (
              <Button {...props} variant="default" size="xs">
                Load a file…
              </Button>
            )}
          </FileButton>
        </Group>
        <Textarea
          label="broker.xml, or any part of it"
          description="The whole file, a <core> section, one section, or single items such as one <address-setting> or <divert>. Nothing is saved until you choose to."
          value={xml}
          onChange={(e) => {
            setXml(e.currentTarget.value);
            setLoaded(null);
            setResult(null);
          }}
          autosize
          minRows={10}
          maxRows={24}
          classNames={{ input: classes.xmlInput }}
        />

        {declaration.declared ? (
          <Radio.Group
            label="Combine with the current declaration"
            description="Merge keeps everything already declared and lets the pasted entries add to or update it; on an address setting the pasted keys win and the other declared keys stay. Replace makes the paste the whole declaration."
            value={combine}
            onChange={(v) => setCombine(v as 'merge' | 'replace')}
          >
            <Group gap="md" mt="xs">
              <Radio value="merge" label="Merge into it" />
              <Radio value="replace" label="Replace it" />
            </Group>
          </Radio.Group>
        ) : null}

        {parse.isError ? <ErrorState error={parse.error} onRetry={preview} /> : null}

        <div aria-live="polite">
          {result && next ? (
            <ImportPreview declaration={declaration} result={result} next={next} merging={merging} />
          ) : null}
        </div>

        {error ? <ErrorState error={error} /> : null}

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
          >
            {`Save as revision ${declaration.revision + 1}`}
          </Button>
        </Group>
        {canSave ? null : (
          <Text size="sm" c="dimmed" ta="end">
            Saving previews first; a document with errors cannot be saved.
          </Text>
        )}
      </Stack>
    </Drawer>
  );
}

/** The declaration as a `<core>` fragment, for the config-managed path and for a copy into broker.xml. */
export function ExportXmlDrawer({
  declaration,
  opened,
  onClose,
}: Readonly<{
  declaration: ConfigDeclarationView;
  opened: boolean;
  onClose: () => void;
}>) {
  const xml = useQuery<string, ApiError>({
    queryKey: ['clusters', declaration.clusterId, 'config', 'xml', declaration.revision],
    queryFn: () => fetchBrokerConfigXml(declaration.clusterId),
    enabled: opened && declaration.declared,
  });
  return (
    <Drawer opened={opened} onClose={onClose} title="broker.xml fragment" position="right" size="xl" padding="md">
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Revision {declaration.revision} as the four sections of a <code>&lt;core&gt;</code> element. Deploy it through
          your own tooling; the next drift evaluation shows whether the running brokers match.
        </Text>
        {xml.isError ? <ErrorState error={xml.error} onRetry={() => void xml.refetch()} /> : null}
        {xml.data ? (
          <>
            <Group justify="flex-end">
              <CopyButton value={xml.data}>
                {({ copied, copy }) => (
                  <Button
                    size="xs"
                    variant="default"
                    onClick={() => {
                      copy();
                      notify.succeeded({ action: COPY, subject: 'the broker.xml fragment' });
                    }}
                  >
                    {copied ? 'Copied' : 'Copy broker.xml fragment'}
                  </Button>
                )}
              </CopyButton>
            </Group>
            <XmlBlock code={xml.data} label={`broker.xml fragment of revision ${declaration.revision}`} />
          </>
        ) : null}
        {!xml.data && xml.isPending && opened ? (
          <LoadingState label="Rendering the broker.xml fragment" blockSize="12rem" />
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
}: Readonly<{
  declaration: ConfigDeclarationView;
  opened: boolean;
  onClose: () => void;
}>) {
  const adopt = useAdoptBrokerConfig(declaration.clusterId);
  const { mutate: read, reset: resetAdopt } = adopt;
  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);

  // A read when the drawer opens; closing it forgets what was read.
  useEffect(() => {
    if (opened) read();
    else {
      resetAdopt();
      reset();
    }
  }, [opened, read, resetAdopt, reset]);

  const result = adopt.data;
  const closes = result?.closes ?? [];
  const saveAdoption = (confirm?: string) =>
    result && save(result.document, 'Adopted from the running cluster', { source: 'ADOPT', confirm });
  return (
    <Drawer opened={opened} onClose={onClose} title="Adopt from cluster" position="right" size="xl" padding="md">
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Reads every live node and builds a declaration from what they run. Nothing is saved until you choose to.{' '}
          {WHY_NOT_AUTOMATIC}
        </Text>
        {adopt.isPending ? <LoadingState label="Reading the live nodes" blockSize="8rem" /> : null}
        {adopt.isError ? <ErrorState error={adopt.error} onRetry={() => read()} /> : null}
        {result ? (
          <div aria-live="polite">
            <Stack gap="md">
              <CountsList current={declaration.document} next={result.document} />
              {result.disagreements.length > 0 ? (
                <Section variant="card" headingLevel={3} title="The nodes disagree">
                  <Text size="sm">The first node's value was taken where they differ; check these before saving.</Text>
                  <List size="sm" spacing="xs">
                    {result.disagreements.map((d) => (
                      <List.Item key={d}>{d}</List.Item>
                    ))}
                  </List>
                </Section>
              ) : null}
              {closes.length > 0 ? (
                <Section
                  variant="card"
                  headingLevel={3}
                  title={`Closes ${closes.length} open drift finding${closes.length === 1 ? '' : 's'} with zero broker writes`}
                >
                  <Text size="sm">
                    Adopting declares what the cluster already runs, so these findings disappear because the declaration
                    moved — not because anything was fixed. Apply the current declaration instead if the cluster is what
                    is wrong.
                  </Text>
                  <List size="sm" spacing="xs">
                    {closes.map((c) => (
                      <List.Item key={`${c.nodeName}:${c.finding.detail}`}>
                        {c.nodeName}: {c.finding.detail}
                      </List.Item>
                    ))}
                  </List>
                </Section>
              ) : null}
              {result.notes.length > 0 ? (
                <List size="sm" spacing="xs">
                  {result.notes.map((n) => (
                    <List.Item key={n}>{n}</List.Item>
                  ))}
                </List>
              ) : null}
            </Stack>
          </div>
        ) : null}
        {error ? <ErrorState error={error} /> : null}
        {closes.length > 0 ? (
          <ConfirmByTyping
            token={declaration.clusterName}
            label={`Type "${declaration.clusterName}" to record that closing these findings is intended`}
            confirmLabel={`Save as revision ${declaration.revision + 1}`}
            tone="default"
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

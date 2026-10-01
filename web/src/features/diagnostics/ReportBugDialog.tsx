import { useState } from 'react';
import {
  Button,
  Code,
  Collapse,
  CopyButton,
  Group,
  Modal,
  Stack,
  Text,
  Textarea,
  TextInput,
  UnstyledButton,
} from '@mantine/core';
import { IconChevronDown, IconChevronRight, IconCopy, IconExternalLink } from '@tabler/icons-react';

import { branding } from '../../branding.ts';
import { useManifest } from '../../kernel/manifest.ts';
import { useDiagnosticsSummary } from './api.ts';
import classes from './Diagnostics.module.css';
import { environmentMarkdown, issueBody, issueUrl, type BugDescription } from './bugReport.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify } from '../../ui/notify.ts';

const COPY = { verb: 'Copy', past: 'Copied', progressive: 'Copying' } as const;

const EMPTY: BugDescription = { happened: '', expected: '', steps: '' };

/**
 * "Report a bug…" in the user menu (diagnostics spec), for every signed-in user: a pre-filled GitHub issue. The environment comes from this Studio only; nothing leaves the browser until the
 * user opens the issue on GitHub, and Copy works where there is no internet at all.
 */
export function ReportBugDialog({ opened, onClose }: Readonly<{ opened: boolean; onClose: () => void }>) {
  const summary = useDiagnosticsSummary(opened);
  const manifest = useManifest();
  const [title, setTitle] = useState('');
  const [titleError, setTitleError] = useState<string | null>(null);
  const [description, setDescription] = useState<BugDescription>(EMPTY);
  const [showEnvironment, setShowEnvironment] = useState(false);

  const environment = environmentMarkdown({
    summary: summary.data,
    signIn: manifest.data?.identityProviders.map((p) => p.label) ?? [],
    plugins: (manifest.data?.features ?? []).filter((f) => f.origin !== 'BUILTIN'),
    browser: navigator.userAgent,
  });
  const body = issueBody(description, environment);
  const markdown = `## ${title.trim() || 'Bug report'}\n\n${body}`;

  const set = (key: keyof BugDescription) => (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.currentTarget.value;
    setDescription((d) => ({ ...d, [key]: value }));
  };

  const close = () => {
    setTitle('');
    setTitleError(null);
    setDescription(EMPTY);
    onClose();
  };

  const open = async () => {
    if (!title.trim()) {
      setTitleError('Give the issue a title before opening it.');
      document.getElementById('bug-title')?.focus();
      return;
    }
    const link = issueUrl(branding.projectUrl, title.trim(), body);
    if (!link.bodyInUrl) {
      // The issue opens either way: a refused clipboard write must not leave the button doing nothing.
      try {
        await navigator.clipboard.writeText(body);
        notify.succeeded({ action: COPY, subject: 'the report to your clipboard; paste it into the issue that opens' });
      } catch {
        notify.failed({
          action: COPY,
          subject: 'the report',
          cause: 'The browser did not allow Studio to write to your clipboard.',
          next: 'Use "Copy as Markdown" in this dialog, then paste the report into the issue that opens.',
        });
      }
    }
    window.open(link.url, '_blank', 'noopener,noreferrer');
  };

  return (
    <Modal opened={opened} onClose={close} title="Report a bug" size="lg">
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Describe what went wrong. {branding.productShortName} adds its versions and environment below; it removes
          secrets, and nothing is sent until you submit the issue on GitHub.
        </Text>
        <TextInput
          id="bug-title"
          label="Title"
          placeholder="One line that says what went wrong"
          value={title}
          error={titleError}
          onChange={(e) => {
            setTitle(e.currentTarget.value);
            if (e.currentTarget.value.trim()) setTitleError(null);
          }}
          onBlur={() => setTitleError(title.trim() ? null : 'Give the issue a title before opening it.')}
          data-autofocus
        />
        <Textarea label="What happened" autosize minRows={3} value={description.happened} onChange={set('happened')} />
        <Textarea
          label="What you expected"
          autosize
          minRows={2}
          value={description.expected}
          onChange={set('expected')}
        />
        <Textarea
          label="Steps to reproduce"
          autosize
          minRows={2}
          placeholder={'1. Open …\n2. Click …'}
          value={description.steps}
          onChange={set('steps')}
        />

        <Stack gap="xs">
          <UnstyledButton
            onClick={() => setShowEnvironment((v) => !v)}
            aria-expanded={showEnvironment}
            aria-controls="bug-environment"
          >
            <Group gap="xs">
              {showEnvironment ? (
                <IconChevronDown size="0.875rem" aria-hidden />
              ) : (
                <IconChevronRight size="0.875rem" aria-hidden />
              )}
              <Text size="sm" fw={600}>
                Environment (included)
              </Text>
              {summary.isPending && <LoadingState variant="inline" label="Reading the environment" />}
            </Group>
          </UnstyledButton>
          {summary.isError && (
            <Stack gap="xs">
              <ErrorState variant="inline" error={summary.error} />
              <Text size="xs">
                {branding.productShortName} could not report its versions. The report says &ldquo;unknown&rdquo; for
                them; add them by hand if you know them.
              </Text>
            </Stack>
          )}
          <Collapse expanded={showEnvironment} id="bug-environment">
            <Code block className={classes.environment}>
              {environment}
            </Code>
          </Collapse>
        </Stack>

        <Group justify="space-between" align="center" wrap="nowrap">
          <Text size="xs" c="dimmed" maw="20rem">
            Opens github.com in a new tab. Nothing is sent until you submit the issue there.
          </Text>
          <Group gap="xs" wrap="nowrap">
            <CopyButton value={markdown}>
              {({ copied, copy }) => (
                <Button variant="default" leftSection={<IconCopy size="0.875rem" aria-hidden />} onClick={copy}>
                  {copied ? 'Copied' : 'Copy as Markdown'}
                </Button>
              )}
            </CopyButton>
            <Button leftSection={<IconExternalLink size="0.875rem" aria-hidden />} onClick={() => void open()}>
              Open on GitHub
            </Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}

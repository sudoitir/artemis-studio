import { useState } from 'react';
import {
  Alert,
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
import { notifications } from '@mantine/notifications';
import { IconChevronDown, IconChevronRight, IconCopy, IconExternalLink } from '@tabler/icons-react';

import { branding } from '../../branding.ts';
import { useManifest } from '../../kernel/manifest.ts';
import { useDiagnosticsSummary } from './api.ts';
import classes from './Diagnostics.module.css';
import { environmentMarkdown, issueBody, issueUrl, type BugDescription } from './bugReport.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';

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
      await navigator.clipboard.writeText(body);
      notifications.show({
        title: 'The report is on your clipboard',
        message: 'It is too long for a link. Paste it into the issue that just opened.',
      });
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

        <Stack gap={6}>
          <UnstyledButton
            onClick={() => setShowEnvironment((v) => !v)}
            aria-expanded={showEnvironment}
            aria-controls="bug-environment"
          >
            <Group gap={6}>
              {showEnvironment ? <IconChevronDown size={14} aria-hidden /> : <IconChevronRight size={14} aria-hidden />}
              <Text size="sm" fw={600}>
                Environment (included)
              </Text>
              {summary.isPending && <LoadingState variant="inline" label="Reading the environment" />}
            </Group>
          </UnstyledButton>
          {summary.isError && (
            <Alert color="yellow" variant="light" p="xs">
              <Text size="xs">
                {branding.productShortName} could not report its versions ({summary.error.message}). The report says
                &ldquo;unknown&rdquo; for them; add them by hand if you know them.
              </Text>
            </Alert>
          )}
          <Collapse expanded={showEnvironment} id="bug-environment">
            <Code block className={classes.environment}>
              {environment}
            </Code>
          </Collapse>
        </Stack>

        <Group justify="space-between" align="center" wrap="nowrap">
          <Text size="xs" c="dimmed" maw={300}>
            Opens github.com in a new tab. Nothing is sent until you submit the issue there.
          </Text>
          <Group gap="xs" wrap="nowrap">
            <CopyButton value={markdown}>
              {({ copied, copy }) => (
                <Button variant="default" leftSection={<IconCopy size={14} aria-hidden />} onClick={copy}>
                  {copied ? 'Copied' : 'Copy as Markdown'}
                </Button>
              )}
            </CopyButton>
            <Button leftSection={<IconExternalLink size={14} aria-hidden />} onClick={() => void open()}>
              Open on GitHub
            </Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}

import { useDeferredValue, useMemo, useState, type ReactNode } from 'react';
import { Badge, Button, Code, CopyButton, Group, ScrollArea, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconCopy, IconSearch } from '@tabler/icons-react';

import type { SectionView } from './api.ts';
import classes from './Diagnostics.module.css';

const MASK = '[redacted]';

/** Splits `text` into plain runs and highlighted `[redacted]` markers, so the admin sees what was removed. */
function withMasks(text: string): ReactNode[] {
  const parts = text.split(MASK);
  return parts.flatMap((part, i) =>
    i === 0
      ? [part]
      : [
          <mark key={i} className={classes.mask}>
            {MASK}
          </mark>,
          part,
        ],
  );
}

/** One section exactly as it goes into the bundle, searchable by line. */
export function SectionPreview({ section, included }: Readonly<{ section: SectionView; included: boolean }>) {
  const [find, setFind] = useState('');
  const query = useDeferredValue(find.trim().toLowerCase());
  const lines = useMemo(() => section.content.split('\n'), [section.content]);
  const shown = useMemo(() => (query ? lines.filter((l) => l.toLowerCase().includes(query)) : lines), [lines, query]);

  return (
    <Stack gap="sm" className={classes.preview}>
      <Group justify="space-between" wrap="nowrap">
        <Group gap="xs" wrap="nowrap">
          <Title order={4}>{section.title}</Title>
          <Code>{section.fileName}</Code>
          {!included && (
            <Badge variant="outline" color="gray">
              Excluded
            </Badge>
          )}
        </Group>
        <CopyButton value={section.content}>
          {({ copied, copy }) => (
            <Button size="compact-sm" variant="default" leftSection={<IconCopy size={14} aria-hidden />} onClick={copy}>
              {copied ? 'Copied' : 'Copy'}
            </Button>
          )}
        </CopyButton>
      </Group>
      <TextInput
        aria-label={`Find in ${section.title}`}
        placeholder={`Find in ${section.title.toLowerCase()}`}
        leftSection={<IconSearch size={14} aria-hidden />}
        value={find}
        onChange={(e) => setFind(e.currentTarget.value)}
        rightSectionWidth={120}
        rightSection={
          query ? (
            <Text size="xs" c="dimmed" className={classes.num} aria-live="polite">
              {shown.length} of {lines.length} lines
            </Text>
          ) : null
        }
      />
      <ScrollArea h={520} className={classes.content} type="auto" offsetScrollbars>
        {section.content.trim() === '' ? (
          <Text size="sm" c="dimmed" p="md">
            This section is empty.
          </Text>
        ) : shown.length === 0 ? (
          <Text size="sm" c="dimmed" p="md">
            No line contains &ldquo;{find.trim()}&rdquo;.{' '}
            <Button variant="subtle" size="compact-xs" onClick={() => setFind('')}>
              Clear the search
            </Button>
          </Text>
        ) : (
          <pre className={classes.text} aria-label={`${section.title} contents`}>
            {withMasks(shown.join('\n'))}
          </pre>
        )}
      </ScrollArea>
    </Stack>
  );
}

import { useDeferredValue, useMemo, useState, type ReactNode } from 'react';
import { Button, Code, CopyButton, Text, TextInput } from '@mantine/core';
import { IconCopy, IconSearch } from '@tabler/icons-react';

import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { SectionView } from './api.ts';
import classes from './Diagnostics.module.css';

const MASK = '[redacted]';

/** Splits `text` into plain runs and highlighted `[redacted]` markers, so the admin sees what was removed. */
function withMasks(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let from = 0;
  for (let at = text.indexOf(MASK); at !== -1; at = text.indexOf(MASK, from)) {
    out.push(
      text.slice(from, at),
      <mark key={`mask-${at}`} className={classes.mask}>
        {MASK}
      </mark>,
    );
    from = at + MASK.length;
  }
  out.push(text.slice(from));
  return out;
}

/** One section exactly as it goes into the bundle, searchable by line. */
export function SectionPreview({ section, included }: Readonly<{ section: SectionView; included: boolean }>) {
  const [find, setFind] = useState('');
  const query = useDeferredValue(find.trim().toLowerCase());
  const lines = useMemo(() => section.content.split('\n'), [section.content]);
  const shown = useMemo(() => (query ? lines.filter((l) => l.toLowerCase().includes(query)) : lines), [lines, query]);

  let body: ReactNode;
  if (section.content.trim() === '') {
    body = (
      <Text size="sm" className={classes.empty}>
        This section is empty.
      </Text>
    );
  } else if (shown.length === 0) {
    body = (
      <Text size="sm" className={classes.empty}>
        No line contains &ldquo;{find.trim()}&rdquo;.{' '}
        <Button variant="subtle" size="compact-xs" onClick={() => setFind('')}>
          Clear the search
        </Button>
      </Text>
    );
  } else {
    body = <pre className={classes.text}>{withMasks(shown.join('\n'))}</pre>;
  }

  return (
    <div className={classes.preview}>
      <Section
        title={section.title}
        headingLevel={3}
        description={<Code>{section.fileName}</Code>}
        actions={
          <>
            {!included && <StatusBadge>Excluded</StatusBadge>}
            <CopyButton value={section.content}>
              {({ copied, copy }) => (
                <Button
                  size="compact-sm"
                  variant="default"
                  leftSection={<IconCopy size="0.875rem" aria-hidden />}
                  onClick={copy}
                >
                  {copied ? 'Copied' : 'Copy'}
                </Button>
              )}
            </CopyButton>
          </>
        }
      >
        <TextInput
          label={`Find in ${section.title}`}
          placeholder="A word or phrase"

          leftSection={<IconSearch size="0.875rem" aria-hidden />}
          value={find}
          onChange={(e) => setFind(e.currentTarget.value)}
          rightSectionWidth="7.5rem"
          rightSection={
            query ? (
              <Text size="xs" className={`${classes.hint} ${classes.num}`} aria-live="polite">
                {shown.length} of {lines.length} lines
              </Text>
            ) : null
          }
        />
        <div className={classes.content} role="region" aria-label={`${section.title} contents`} tabIndex={0}>
          {body}
        </div>
      </Section>
    </div>
  );
}

import { useEffect, useRef, type KeyboardEvent } from 'react';
import { ActionIcon, Group, Text, TextInput } from '@mantine/core';
import { IconChevronDown, IconChevronUp, IconX } from '@tabler/icons-react';

import { findSummary } from './gridFind.ts';
import classes from './DataTable.module.css';

interface GridFindProps {
  /** The grid's accessible name, which names the find. */
  label: string;
  query: string;
  onQuery: (query: string) => void;
  count: number;
  current: number;
  onStep: (by: 1 | -1) => void;
  onClose: () => void;
}

/**
 * The find of a virtualised grid: the browser's own cannot see rows that are not rendered. Enter
 * and Shift+Enter move between matches, Escape closes it; the result is announced politely.
 */
export function GridFind({ label, query, onQuery, count, current, onStep, onClose }: Readonly<GridFindProps>) {
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    input.current?.focus();
    input.current?.select();
  }, []);

  const onKeyDown = (e: KeyboardEvent<HTMLElement>) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      onClose();
    } else if (e.key === 'Enter') {
      e.preventDefault();
      onStep(e.shiftKey ? -1 : 1);
    }
  };

  return (
    <Group className={classes.find} gap="xs" wrap="nowrap" role="search" aria-label={`Find in ${label}`}>
      <TextInput
        ref={input}
        size="xs"
        aria-label={`Find in ${label}`}
        placeholder="Find in all rows"
        value={query}
        onChange={(e) => onQuery(e.currentTarget.value)}
        onKeyDown={onKeyDown}
      />
      <Text size="xs" c="dimmed" className={classes.findCount} role="status" aria-live="polite">
        {findSummary(query, count, current)}
      </Text>
      <ActionIcon
        size="sm"
        variant="subtle"
        aria-label="Previous match"
        disabled={count === 0}
        onClick={() => onStep(-1)}
      >
        <IconChevronUp size={14} />
      </ActionIcon>
      <ActionIcon size="sm" variant="subtle" aria-label="Next match" disabled={count === 0} onClick={() => onStep(1)}>
        <IconChevronDown size={14} />
      </ActionIcon>
      <ActionIcon size="sm" variant="subtle" aria-label="Close find" onClick={onClose}>
        <IconX size={14} />
      </ActionIcon>
    </Group>
  );
}

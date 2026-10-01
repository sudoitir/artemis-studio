import { Code } from '@mantine/core';

/**
 * A broker.xml fragment to read and copy: a block that scrolls when a line is long, so the block
 * itself takes focus (a keyboard cannot reach what only a pointer scrolls) and is named for what it
 * holds.
 */
export function XmlBlock({ code, label }: Readonly<{ code: string; label: string }>) {
  return (
    <Code block tabIndex={0} role="region" aria-label={label}>
      {code}
    </Code>
  );
}

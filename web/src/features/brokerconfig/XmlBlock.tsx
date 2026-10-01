import { InlineCodeHighlight } from '@mantine/code-highlight';

import classes from './XmlBlock.module.css';

/**
 * A broker.xml fragment to read and copy, coloured like every other code block. The block scrolls
 * when a line is long, so the block itself takes focus (a keyboard cannot reach what only a pointer
 * scrolls) and is named for what it holds.
 */
export function XmlBlock({ code, label }: Readonly<{ code: string; label: string }>) {
  return (
    <section tabIndex={0} aria-label={label} className={classes.scroller}>
      <InlineCodeHighlight code={code} language="xml" className={classes.code} />
    </section>
  );
}

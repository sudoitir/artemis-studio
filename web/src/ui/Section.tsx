import { useId, type ReactNode, type Ref } from 'react';
import { Text, Title } from '@mantine/core';

import classes from './Section.module.css';

/**
 * A titled block of a page. It is a region named by its heading, so a screen reader can jump to it.
 *
 * <p>The heading is an h2 by default, or an h3 inside another section; a page never skips a level.
 * `variant="card"` frames the block as a raised panel, for content that should read as one unit.
 *
 * <p>`ref` and `tabIndex` let a page move focus into a section, such as to the one a skip link or a
 * validation summary points at: `tabIndex={-1}` makes it focusable by script without a tab stop.
 */
export function Section({
  title,
  headingLevel = 2,
  description,
  actions,
  variant = 'plain',
  ref,
  tabIndex,
  children,
}: Readonly<{
  title: string;
  /** 2 for a section of the page, 3 for one nested in a section. */
  headingLevel?: 2 | 3;
  description?: ReactNode;
  /** Controls for the section as a whole, on the end side of the heading. */
  actions?: ReactNode;
  variant?: 'plain' | 'card';
  ref?: Ref<HTMLElement>;
  tabIndex?: number;
  children?: ReactNode;
}>) {
  const titleId = useId();
  return (
    <section ref={ref} tabIndex={tabIndex} className={classes.section} data-variant={variant} aria-labelledby={titleId}>
      <div className={classes.header}>
        <div className={classes.heading}>
          <Title id={titleId} order={headingLevel} className={classes.title}>
            {title}
          </Title>
          {description ? (
            <Text size="sm" component="div" className={classes.description}>
              {description}
            </Text>
          ) : null}
        </div>
        {actions ? <div className={classes.actions}>{actions}</div> : null}
      </div>
      {children}
    </section>
  );
}

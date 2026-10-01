import type { ReactNode } from 'react';
import { Text, Title } from '@mantine/core';

import classes from './PageHeader.module.css';

/**
 * The page's one top-level heading, with what orients the operator beside it.
 *
 * <p>Every page renders exactly one of these; its sections follow it as `Section` (h2, then h3).
 * The cluster a page belongs to goes in `meta`, next to the title, never as a second h1.
 * The header wraps by its content's size, so it needs no breakpoints.
 */
export function PageHeader({
  title,
  description,
  meta,
  actions,
}: Readonly<{
  /** Names the view; rendered as the page's h1. */
  title: string;
  /** One or two sentences on what the view shows. */
  description?: ReactNode;
  /** Context beside the title, such as the cluster and its environment. */
  meta?: ReactNode;
  /** The page's own actions, on the end side. */
  actions?: ReactNode;
}>) {
  return (
    <div className={classes.root}>
      <div className={classes.main}>
        <div className={classes.heading}>
          <Title order={1} className={classes.title}>
            {title}
          </Title>
          {meta ? <div className={classes.meta}>{meta}</div> : null}
        </div>
        {description ? (
          <Text size="sm" component="div" className={classes.description}>
            {description}
          </Text>
        ) : null}
      </div>
      {actions ? <div className={classes.actions}>{actions}</div> : null}
    </div>
  );
}

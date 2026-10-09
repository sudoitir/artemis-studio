import { useId, type ReactNode } from 'react';
import { Text } from '@mantine/core';

import styles from './NavGroup.module.css';

/**
 * A group of navigation rows under a heading, shared by the sidebar and every section list. The
 * heading names the group but is not an HTML heading: the page's headings are the page's own. Collapsed
 * (the sidebar's rail), the heading is drawn as a rule in the same box, so the icons below it keep their
 * place, and its name stays available to a screen reader.
 */
export function NavGroup({
  label,
  collapsed = false,
  sticky = false,
  children,
}: Readonly<{ label: string; collapsed?: boolean; sticky?: boolean; children: ReactNode }>) {
  const headingId = useId();
  const heading = (
    <Text
      id={headingId}
      component="span"
      className={styles.heading}
      data-sticky={sticky || undefined}
      data-collapsed={collapsed || undefined}
    >
      {label}
    </Text>
  );
  return (
    <div role="group" aria-labelledby={headingId} className={styles.group}>
      {heading}
      {children}
    </div>
  );
}

import { useId, type ReactNode } from 'react';
import { Divider, Text, VisuallyHidden } from '@mantine/core';

import styles from './NavGroup.module.css';

/**
 * A group of navigation rows under a heading, shared by the sidebar and every section list. The
 * heading names the group but is not an HTML heading: the page's headings are the page's own. Collapsed
 * (the sidebar's rail), it is a divider, and the name stays available to a screen reader.
 */
export function NavGroup({
  label,
  collapsed = false,
  sticky = false,
  children,
}: Readonly<{ label: string; collapsed?: boolean; sticky?: boolean; children: ReactNode }>) {
  const headingId = useId();
  const heading = (
    <Text id={headingId} component="span" className={styles.heading} data-sticky={sticky || undefined}>
      {label}
    </Text>
  );
  return (
    <div role="group" aria-labelledby={headingId} className={styles.group}>
      {collapsed ? (
        <>
          <Divider my="xs" />
          <VisuallyHidden id={headingId}>{label}</VisuallyHidden>
        </>
      ) : (
        heading
      )}
      {children}
    </div>
  );
}

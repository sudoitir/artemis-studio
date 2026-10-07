import type { MouseEvent, ReactNode } from 'react';
import { Text, VisuallyHidden } from '@mantine/core';
import { IconAlertOctagon, IconAlertTriangle, IconCircleCheck, IconInfoCircle } from '@tabler/icons-react';
import { useRouter } from '@tanstack/react-router';

import { Ago } from '../time/Ago.tsx';
import { isInAppPath, type InboxItem, type InboxSeverity } from './api.ts';
import classes from './Inbox.module.css';

const SEVERITY: Record<InboxSeverity, { Icon: typeof IconInfoCircle; word: string }> = {
  info: { Icon: IconInfoCircle, word: 'Information' },
  success: { Icon: IconCircleCheck, word: 'Done' },
  warning: { Icon: IconAlertTriangle, word: 'Warning' },
  danger: { Icon: IconAlertOctagon, word: 'Critical' },
};

/**
 * One notice, in the header's popover and on the inbox page: its severity, whether it is unread, its title
 * (a link when it has one), the body on the page, and when it arrived. Opening it marks it read.
 *
 * <p>The severity and the unread state are in words for assistive technology; the icon and the dot repeat
 * them. A warning or critical notice is the only one with colour.
 */
export function NoticeRow({
  item,
  now,
  onOpen,
  showBody = false,
  actions,
}: Readonly<{
  item: InboxItem;
  now: number;
  /** Called when the notice is opened: followed, or for one without a link, activated. */
  onOpen: (item: InboxItem) => void;
  showBody?: boolean;
  actions?: ReactNode;
}>) {
  const router = useRouter();
  const unread = !item.readAt;
  const { Icon, word } = SEVERITY[item.severity] ?? SEVERITY.info;
  const link = isInAppPath(item.link) ? item.link : undefined;

  const follow = (event: MouseEvent<HTMLAnchorElement>) => {
    onOpen(item);
    if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    // Through the router's history, so a link with a search string arrives as one, without a page load.
    event.preventDefault();
    router.history.push(link);
  };

  const label = (
    <>
      {/* The space is its own text node: one inside the hidden span is dropped from the accessible name. */}
      {unread ? <VisuallyHidden component="span">Unread,</VisuallyHidden> : null} {item.title}
    </>
  );
  let title: ReactNode;
  if (link) {
    title = (
      <a className={classes.title} href={link} onClick={follow}>
        {label}
      </a>
    );
  } else if (unread) {
    title = (
      <button type="button" className={classes.title} onClick={() => onOpen(item)}>
        {label}
      </button>
    );
  } else {
    title = <span className={classes.title}>{label}</span>;
  }

  return (
    <li className={classes.notice} data-unread={unread || undefined}>
      <span className={classes.mark} data-severity={item.severity}>
        <span className={classes.dot} aria-hidden />
        <Icon size={16} aria-hidden />
        <VisuallyHidden component="span">{word}</VisuallyHidden>
      </span>
      <div className={classes.words}>
        {title}
        {showBody && item.body ? (
          <Text size="sm" className={classes.body}>
            {item.body}
          </Text>
        ) : null}
        <div className={classes.when}>
          <Ago at={item.createdAt} now={now} />
        </div>
      </div>
      {actions ? <div className={classes.actions}>{actions}</div> : <span />}
    </li>
  );
}

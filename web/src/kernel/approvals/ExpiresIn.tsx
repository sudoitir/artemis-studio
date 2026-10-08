import { useEffect, useEffectEvent, useState } from 'react';
import { Text, VisuallyHidden } from '@mantine/core';

import { absoluteLabel, useServerNow } from '../time/time.ts';
import timeClasses from '../time/Time.module.css';
import { expiryMark, remainingLabel } from './words.ts';

/**
 * A live countdown to when a request lapses, with the exact time beside it. The figure ticks every second, but
 * it is not a live region, so a screen reader reads it only when it reaches it; what is announced is one polite
 * line at the marks that matter (an hour, 15, 5 and 1 minutes left, and expiry), never a tick. `onExpired` runs
 * once when the time is up, so the page can ask what became of the request.
 */
export function ExpiresIn({ at, onExpired }: Readonly<{ at: string; onExpired?: () => void }>) {
  const now = useServerNow(1_000);
  const left = Date.parse(at) - now;
  const mark = expiryMark(left);
  // The mark the page opened at is read with the page; only a mark reached while it is open is announced.
  const [seen, setSeen] = useState(mark);
  const [announced, setAnnounced] = useState('');
  if (mark !== seen) {
    setSeen(mark);
    setAnnounced(mark ?? '');
  }
  const expired = left <= 0;
  const expire = useEffectEvent(() => onExpired?.());
  useEffect(() => {
    if (expired) expire();
  }, [expired]);

  return (
    <>
      <Text size="sm" component="span" className={timeClasses.figures}>
        <time dateTime={at}>{expired ? 'Time is up' : `${remainingLabel(left)} left`}</time>
        <Text span size="xs" c="dimmed">
          {' '}
          ({absoluteLabel(at)})
        </Text>
      </Text>
      <VisuallyHidden aria-live="polite" aria-atomic>
        {announced}
      </VisuallyHidden>
    </>
  );
}

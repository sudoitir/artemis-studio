import { useLayoutEffect, useRef, useState } from 'react';

import classes from './DataTable.module.css';

/** How much of the end of an identifier always stays visible: its distinguishing suffix. */
const TAIL_CHARS = 12;

/**
 * An identifier shortened in the middle: the start shrinks and the last characters stay, so names that
 * share a prefix (`DLQ.orders.a1b2…`, `DLQ.orders.c3d4…`) stay distinguishable. The full value is the
 * cell's `title` and its reveal panel, and the clipped start stays in the DOM, so it can still be
 * selected and read.
 *
 * The ellipsis is the tail's own leading mark, shown only while the start is clipped. `text-overflow`
 * would put it right after the last whole character of the start and leave the rest of the start's box
 * empty between it and the tail: a gap of up to one character. Here the start's box ends exactly where
 * the ellipsis begins, so the start, the ellipsis and the tail abut.
 */
export function MiddleTruncate({ text }: Readonly<{ text: string }>) {
  const chars = Array.from(text);
  if (chars.length <= TAIL_CHARS) return <>{text}</>;
  return <Shortened start={chars.slice(0, -TAIL_CHARS).join('')} tail={chars.slice(-TAIL_CHARS).join('')} />;
}

function Shortened({ start, tail }: Readonly<{ start: string; tail: string }>) {
  const startRef = useRef<HTMLSpanElement>(null);
  const [clipped, setClipped] = useState(false);
  // Before paint, then whenever the start's box changes size: the ellipsis never shows a frame late.
  useLayoutEffect(() => {
    const element = startRef.current;
    if (!element) return;
    const measure = () => setClipped(element.scrollWidth > element.clientWidth);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [start]);
  return (
    <span className={classes.middle}>
      <span ref={startRef} data-clip data-clipped={clipped || undefined} className={classes.middleStart}>
        {start}
      </span>
      <span data-ellipsis={clipped || undefined} className={classes.middleTail}>
        {tail}
      </span>
    </span>
  );
}

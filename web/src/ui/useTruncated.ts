import { useEffect, useRef, useState } from 'react';

/**
 * Whether the element's text is cut off by an ellipsis right now, so a `title` is added only when it
 * tells the reader something the row does not already show. `text` re-measures when the words change.
 */
export function useTruncated<T extends HTMLElement>(text: string) {
  const ref = useRef<T>(null);
  const [truncated, setTruncated] = useState(false);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const measure = () => setTruncated(element.scrollWidth > element.clientWidth);
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [text]);
  return { ref, truncated };
}

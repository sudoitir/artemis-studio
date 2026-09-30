import { VisuallyHidden } from '@mantine/core';

import { absoluteLabel, elapsedLabel } from './time.ts';

/**
 * A moment in words (or, with `future`, how long from now) inside a `<time>`, with the exact time as text
 * a screen reader reads and a hover shows. For running text, where {@link When} would take a block of its own.
 */
export function Ago({ at, now, future = false }: Readonly<{ at: string; now: number; future?: boolean }>) {
  const ms = future ? Date.parse(at) - now : now - Date.parse(at);
  const exact = absoluteLabel(at);
  return (
    <>
      <time dateTime={at} title={exact}>
        {future ? `in ${elapsedLabel(ms)}` : `${elapsedLabel(ms)} ago`}
      </time>
      <VisuallyHidden component="span"> ({exact})</VisuallyHidden>
    </>
  );
}

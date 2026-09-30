import { Text } from '@mantine/core';

import { absoluteLabel, elapsedLabel } from './time.ts';

/**
 * How long ago (or, with `future`, how long from now), in words, with the exact time beneath it so
 * nothing is hidden behind a hover. `now` is {@link useServerNow}'s, so every row shares one clock.
 */
export function When({ at, now, future = false }: Readonly<{ at: string; now: number; future?: boolean }>) {
  const ms = future ? Date.parse(at) - now : now - Date.parse(at);
  return (
    <>
      <Text size="sm" component="time" dateTime={at} display="block">
        {future ? `in ${elapsedLabel(ms)}` : `${elapsedLabel(ms)} ago`}
      </Text>
      <Text size="xs" c="dimmed">
        {absoluteLabel(at)}
      </Text>
    </>
  );
}

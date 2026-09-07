import { Select, Stack, Text } from '@mantine/core';

import { absoluteLabel, useServerNow } from '../app/time.ts';
import {
  AUTO,
  displayZone,
  localZone,
  setDisplayZone,
  useDisplayZone,
  zoneOptions,
} from '../app/timezone.ts';

/**
 * The operator's own display preferences.
 *
 * Deliberately the one section on this screen that is *not* ADR-0047 plane A.
 * Everything else here is stored in Postgres, audited, gated on `settings:write`
 * and shared by every user; this is browser-local, personal, needs no permission
 * and is not worth an audit row. Presenting the two as one list would misrepresent
 * both — hence the separate section and the explicit sentence saying so.
 */
export function DisplayPreferences() {
  const preference = useDisplayZone();
  // A live preview needs a live clock, and it must be Studio's — previewing the
  // workstation's clock in a chosen zone would answer the wrong question, since
  // the whole point of `app/time.ts` is that the workstation's clock may be wrong.
  const now = useServerNow();

  const resolved = displayZone();
  const groups = zoneOptions();

  return (
    <Stack gap="xs" maw={440}>
      <Select
        label="Timezone"
        description={
          'The zone every timestamp on every screen is written in. Display only — nothing ' +
          'Studio stores or sends to a broker changes, and no other operator’s screen is ' +
          'affected.'
        }
        data={groups}
        value={preference}
        onChange={(next) => next && setDisplayZone(next)}
        searchable
        nothingFoundMessage="No timezone matches that"
        allowDeselect={false}
        size="xs"
        comboboxProps={{ withinPortal: true }}
      />

      {/*
        The preview is the point of this control. An IANA name is not something an
        operator can check in their head; the wall clock it produces is. Ticking,
        so it reads as live rather than as a frozen example.
      */}
      <Text size="xs" c="dimmed">
        Right now this reads{' '}
        <Text span ff="monospace" fw={500} c="var(--as-text)">
          {absoluteLabel(now)}
        </Text>
      </Text>

      {preference === AUTO ? (
        <Text size="xs" c="dimmed">
          Detected from this browser, currently <strong>{localZone()}</strong>, and it follows the
          machine if that changes. Choose a zone above to pin it instead &mdash; a pinned choice is
          kept and is never reset back to automatic.
        </Text>
      ) : resolved === 'UTC' ? (
        <Text size="xs" c="dimmed">
          UTC, which is what Studio&rsquo;s container and the broker logs you are likely correlating
          against are in.
        </Text>
      ) : (
        <Text size="xs" c="dimmed">
          Pinned to <strong>{resolved}</strong>, so it stays put wherever this browser is. Every
          timestamp names its offset, so a screen can still be lined up against a UTC log.
        </Text>
      )}
    </Stack>
  );
}

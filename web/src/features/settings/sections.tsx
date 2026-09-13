import { Text } from '@mantine/core';

import { DisplayPreferences } from './DisplayPreferences.tsx';
import { OperationalConfig } from './OperationalConfig.tsx';

/** Settings section: the operator's own display preferences, first and separate from what is shared. */
export function DisplaySection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Yours alone. Stored in this browser, applied immediately, and never sent to the server —
        changing it needs no permission and affects nobody else&rsquo;s screen.
      </Text>
      <DisplayPreferences />
    </>
  );
}

/** Settings section: Studio's operational configuration, stored in Postgres. */
export function OperationalSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Overrides the packaged defaults. Stored in Postgres, not the container, and
        applied without a restart. Reset clears the override and the packaged default
        takes over again.
      </Text>
      <OperationalConfig />
    </>
  );
}

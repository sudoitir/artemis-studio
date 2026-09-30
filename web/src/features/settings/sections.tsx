import { Text } from '@mantine/core';

import { DisplayPreferences } from './DisplayPreferences.tsx';
import { OperationalConfig } from './OperationalConfig.tsx';
import { SecuritySettings } from './SecuritySettings.tsx';
import { StudioHealth } from './StudioHealth.tsx';

/** Settings section: the operator's own display preferences, first and separate from what is shared. */
export function DisplaySection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Yours alone. Stored in this browser, applied immediately, and never sent to the server — changing it needs no
        permission and affects nobody else&rsquo;s screen.
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
        Overrides the packaged defaults. Stored in Postgres, not the container, and applied without a restart. Reset
        clears the override and the packaged default takes over again.
      </Text>
      <OperationalConfig />
    </>
  );
}

/** Settings section: where Studio's key-encryption key comes from, and rotating it. */
export function SecuritySection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Studio encrypts the secrets it stores (connection passwords, tokens) under a key that lives in your key
        provider, never in Studio&rsquo;s database. Rotating re-wraps them under a newer key version.
      </Text>
      <SecuritySettings />
    </>
  );
}

/** Settings section: Studio's own health, so an operator can tell whether it is Studio or the broker that is behind. */
export function HealthSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Studio watching itself: its background jobs, its calls to each broker node, its database connections and its
        open event streams. Refreshed every 5 seconds.
      </Text>
      <StudioHealth />
    </>
  );
}

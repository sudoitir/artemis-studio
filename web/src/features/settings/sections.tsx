import { Text } from '@mantine/core';

import { DisplayPreferences } from './DisplayPreferences.tsx';
import { OperationalConfig } from './OperationalConfig.tsx';
import { SecuritySettings } from './SecuritySettings.tsx';

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

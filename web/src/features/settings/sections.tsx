import { Text } from '@mantine/core';

import { Section } from '../../ui/Section.tsx';
import { DisplayPreferences } from './DisplayPreferences.tsx';
import { SecuritySettings } from './SecuritySettings.tsx';
import { RahdarHealth } from './RahdarHealth.tsx';

/** Account section: the operator's own display preferences, apart from anything a cluster's settings share. */
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

/** Administration tab: where Rahdar's key-encryption key comes from, and rotating it. It is the installation's, not a cluster's. */
export function EncryptionKeysPanel() {
  return (
    <Section
      title="Encryption keys"
      description="Rahdar encrypts the secrets it stores (connection passwords, tokens) under a key that lives in your key provider, never in Rahdar’s database. Rotating re-wraps them under a newer key version."
    >
      <SecuritySettings />
    </Section>
  );
}

/** Administration tab: Rahdar's own health, so an operator can tell whether it is Rahdar or the broker that is behind. */
export function RahdarHealthPanel() {
  return (
    <Section
      title="Rahdar health"
      description="Rahdar watching itself: its background jobs, its calls to each broker node, its database connections and its open event streams. Refreshed every 5 seconds."
    >
      <RahdarHealth />
    </Section>
  );
}

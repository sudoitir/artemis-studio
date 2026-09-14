import { Text } from '@mantine/core';

import { IndexSubscriptions } from './IndexSubscriptions.tsx';

/** Settings section: which queues the SQL Console keeps a searchable copy of. */
export function IndexSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Which queues the SQL Console keeps a searchable copy of, so a question can be answered
        after the message has been consumed. Off by default: an index holds message payload, and
        starting one is a deliberate, audited choice with a retention period attached.
      </Text>
      <IndexSubscriptions />
    </>
  );
}

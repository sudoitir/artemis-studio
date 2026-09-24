import { Text } from '@mantine/core';

import { NotificationChannels } from './NotificationChannels.tsx';

/** Settings section: where alert rules can send a notification. */
export function ChannelsSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Slack, Microsoft Teams, PagerDuty, email and signed-webhook destinations alert rules can route to — global, not per cluster, since
        one channel commonly serves several clusters.
      </Text>
      <NotificationChannels />
    </>
  );
}

import { Fragment } from 'react';
import { Divider, Stack, Title } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { useSlot } from '../slots.ts';

/**
 * A cluster's Settings page: the sections the features contribute, in order. The first are the
 * operator's own display preferences, then Studio's operational configuration, then what belongs to
 * this cluster — personal, then shared and audited, reads as the escalation it is.
 */
export function SettingsView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const sections = useSlot('settings.sections');

  return (
    <Stack gap="xl" maw={640}>
      {sections.map(({ id, title, Component }, index) => (
        <Fragment key={id}>
          {index > 0 ? <Divider /> : null}
          <div>
            <Title order={3}>{title}</Title>
            <Component clusterId={clusterId} />
          </div>
        </Fragment>
      ))}
    </Stack>
  );
}

import { Anchor, Code, Stack, Text, Title } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { branding } from '../../branding.ts';

/**
 * What a disabled feature's address shows (feature-modules spec): that the feature is off on this
 * installation rather than missing or broken, the startup property that turns it on, and the way
 * back. Never a blank page or a not-found, which would teach the operator the view does not exist.
 */
export function FeatureDisabled({
  title,
  property,
  clusterId,
}: {
  /** The feature's title, as the manifest names it. */
  title: string;
  /** The startup property that enables it, as the manifest names it. */
  property: string;
  /** The cluster the address belongs to, when it is a cluster view. */
  clusterId?: string;
}) {
  return (
    <Stack gap="sm" maw={560}>
      <Title order={2} fz="h3">
        {title} is disabled on this installation
      </Title>
      <Text size="sm">
        This {branding.productShortName} was started with {title} turned off, so its screens, API
        and assistant tools are not available here. An administrator turns it on by restarting with
      </Text>
      <Code block>{property}=true</Code>
      {clusterId ? (
        <Anchor component={Link} to={`/clusters/${clusterId}`} size="sm">
          Back to the cluster
        </Anchor>
      ) : (
        <Anchor component={Link} to="/" size="sm">
          Back to {branding.productShortName}
        </Anchor>
      )}
    </Stack>
  );
}

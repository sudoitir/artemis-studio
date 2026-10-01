import { Anchor, Code } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { branding } from '../../branding.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';

/**
 * What a disabled feature's address shows (feature-modules spec): that the feature is off on this
 * installation rather than missing or broken, the startup property that turns it on, and the way
 * back. Never a blank page or a not-found, which would teach the operator the view does not exist.
 */
export function FeatureDisabled({
  title,
  property,
  clusterId,
}: Readonly<{
  /** The feature's title, as the manifest names it. */
  title: string;
  /** The startup property that enables it, as the manifest names it. */
  property: string;
  /** The cluster the address belongs to, when it is a cluster view. */
  clusterId?: string;
}>) {
  return (
    <Page>
      <PageHeader
        title={`${title} is disabled on this installation`}
        description={`This ${branding.productShortName} was started with ${title} turned off, so its screens, API and assistant tools are not available here.`}
      />
      <Section title="Turn it on" description="An administrator turns it on by restarting with">
        <Code block>{property}=true</Code>
      </Section>
      <div>
        <Anchor component={Link} to={clusterId ? `/clusters/${clusterId}` : '/'} size="sm" underline="always">
          {clusterId ? 'Back to the cluster' : `Back to ${branding.productShortName}`}
        </Anchor>
      </div>
    </Page>
  );
}

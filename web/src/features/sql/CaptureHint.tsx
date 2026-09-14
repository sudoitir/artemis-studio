import { Alert, Anchor, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useIndexSubscriptions } from './api.ts';
import { uncapturedAddresses } from './captureCoverage.ts';

/**
 * A short nudge where something depends on seeing every message: which of these addresses
 * are only sampled, and where capture is turned on. Renders nothing once every one of them
 * is captured, and nothing when subscriptions cannot be read — it is advice, not a state.
 */
export function CaptureHint(props: { clusterId: string; addresses: string[]; purpose: string }) {
  // Nothing to check means nothing to fetch.
  return props.addresses.length === 0 ? null : <CoverageHint {...props} />;
}

function CoverageHint({ clusterId, addresses, purpose }: { clusterId: string; addresses: string[]; purpose: string }) {
  const subscriptions = useIndexSubscriptions(clusterId);
  if (!subscriptions.data) return null;
  const missing = uncapturedAddresses(subscriptions.data, addresses);
  if (missing.length === 0) return null;
  return (
    <Alert color="gray" variant="light" title="Only sampled">
      <Text size="sm">
        {missing.join(', ')} {missing.length === 1 ? 'is' : 'are'} not captured, so {purpose} sees only the
        messages a sample happened to catch. Turn on &ldquo;Capture everything&rdquo; for{' '}
        {missing.length === 1 ? 'it' : 'them'} under{' '}
        <Anchor component={Link} to={`/clusters/${clusterId}/settings`} size="sm">
          Settings → Message index
        </Anchor>
        .
      </Text>
    </Alert>
  );
}

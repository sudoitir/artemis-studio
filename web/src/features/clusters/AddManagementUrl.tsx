import { useState } from 'react';
import { Alert, Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { useOverrideNodeUrl, type NodeEndpointView } from './api.ts';

/**
 * "Found, not yet manageable" → give a discovered node a reachable management
 * URL. This flow is a normal next step, not an error (Phase 0: the common
 * containerised case).
 */
export function AddManagementUrl({
  clusterId,
  endpoint,
  opened,
  onClose,
}: Readonly<{
  clusterId: string;
  endpoint: NodeEndpointView | null;
  opened: boolean;
  onClose: () => void;
}>) {
  const [url, setUrl] = useState('');
  const [coreUrl, setCoreUrl] = useState('');
  const override = useOverrideNodeUrl(clusterId);

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={endpoint ? `Add a management URL for ${endpoint.coreUrl}` : ''}
      size="lg"
    >
      <Stack gap="sm">
        <Text size="sm" c="dimmed">
          Its pair reported <code>{endpoint?.coreUrl}</code>. That is a broker-to-broker connector, not a management
          URL, so Studio cannot reach it yet. Enter the Jolokia URL you can reach this broker on.
        </Text>
        <TextInput
          label="Management URL"
          placeholder="http://broker-2:8261/console/jolokia"
          value={url}
          onChange={(e) => setUrl(e.currentTarget.value)}
        />
        <TextInput
          label="Core URL"
          description="Optional. Set this when the advertised connector is not reachable from Studio (needed for live events)."
          placeholder="tcp://broker-2:61617"
          value={coreUrl}
          onChange={(e) => setCoreUrl(e.currentTarget.value)}
        />
        <div aria-live="polite">
          {override.isError ? (
            <Alert color="red" variant="light" title={override.error.title}>
              {override.error.message}
            </Alert>
          ) : null}
        </div>
        <Group justify="flex-end">
          <Button variant="subtle" onClick={onClose}>
            Cancel
          </Button>
          <Button
            loading={override.isPending}
            disabled={(!url && !coreUrl) || !endpoint}
            onClick={() =>
              endpoint &&
              override.mutate(
                {
                  nodeId: endpoint.id,
                  jolokiaUrl: url || undefined,
                  coreUrl: coreUrl || undefined,
                },
                {
                  onSuccess: () => {
                    notifications.show({
                      title: 'Node URL updated',
                      message: endpoint.coreUrl ?? endpoint.name,
                    });
                    setUrl('');
                    setCoreUrl('');
                    onClose();
                  },
                },
              )
            }
          >
            Save
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

import { useEffect, useRef, useState } from 'react';
import { Button, Modal, Stack, Text, TextInput } from '@mantine/core';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useOverrideNodeUrl, type NodeEndpointView } from './api.ts';
import classes from './Clusters.module.css';

const UPDATE: ActionVerb = { verb: 'Update', past: 'Updated', progressive: 'Updating' };

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
  const [missing, setMissing] = useState(false);
  const [rejected, setRejected] = useState(0);
  const urlRef = useRef<HTMLInputElement>(null);
  const override = useOverrideNodeUrl(clusterId);

  // A rejected press takes the first field into focus, where the message is.
  useEffect(() => {
    if (rejected > 0) urlRef.current?.focus();
  }, [rejected]);

  const save = () => {
    if (!endpoint) return;
    if (!url.trim() && !coreUrl.trim()) {
      setMissing(true);
      setRejected((n) => n + 1);
      return;
    }
    override.mutate(
      {
        nodeId: endpoint.id,
        jolokiaUrl: url.trim() || undefined,
        coreUrl: coreUrl.trim() || undefined,
      },
      {
        onSuccess: () => {
          notify.succeeded({ action: UPDATE, subject: `the URL of node ${endpoint.coreUrl ?? endpoint.name}` });
          setUrl('');
          setCoreUrl('');
          onClose();
        },
      },
    );
  };

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={endpoint ? `Add a management URL for ${endpoint.coreUrl}` : 'Add a management URL'}
      size="lg"
    >
      <form
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
      >
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            Its pair reported <code>{endpoint?.coreUrl}</code>. That is a broker-to-broker connector, not a management
            URL, so Studio cannot reach it yet. Enter the Jolokia URL you can reach this broker on.
          </Text>
          <TextInput
            ref={urlRef}
            label="Management URL"
            placeholder="http://broker-2:8261/console/jolokia"
            value={url}
            error={
              missing && !url.trim() && !coreUrl.trim() ? 'Enter a management URL, a Core URL, or both.' : undefined
            }
            onChange={(e) => {
              setUrl(e.currentTarget.value);
              setMissing(false);
            }}
          />
          <TextInput
            label="Core URL"
            description="Optional. Set this when the advertised connector is not reachable from Studio (needed for live events)."
            placeholder="tcp://broker-2:61617"
            value={coreUrl}
            onChange={(e) => {
              setCoreUrl(e.currentTarget.value);
              setMissing(false);
            }}
          />
          {override.isError ? <ErrorState variant="inline" error={override.error} /> : null}
          <div className={classes.actions}>
            <Button variant="subtle" onClick={onClose} disabled={override.isPending}>
              Cancel
            </Button>
            <Button type="submit" loading={override.isPending}>
              Save
            </Button>
          </div>
        </Stack>
      </form>
    </Modal>
  );
}

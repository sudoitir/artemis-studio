import { Button, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
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
  const override = useOverrideNodeUrl(clusterId);
  const form = useForm({
    initialValues: { url: '', coreUrl: '' },
    validateInputOnBlur: true,
    validate: {
      url: (v, values) => (!v.trim() && !values.coreUrl.trim() ? 'Enter a management URL, a Core URL, or both.' : null),
    },
    // Either URL answers the message, which sits beside the first.
    onValuesChange: (values) => {
      if (values.coreUrl.trim()) form.clearFieldError('url');
    },
  });

  const save = form.onSubmit(({ url, coreUrl }) => {
    if (!endpoint) return;
    override.mutate(
      {
        nodeId: endpoint.id,
        jolokiaUrl: url.trim() || undefined,
        coreUrl: coreUrl.trim() || undefined,
      },
      {
        onSuccess: () => {
          notify.succeeded({ action: UPDATE, subject: `the URL of node ${endpoint.coreUrl ?? endpoint.name}` });
          form.reset();
          onClose();
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={endpoint ? `Add a management URL for ${endpoint.coreUrl}` : 'Add a management URL'}
      size="lg"
    >
      <form noValidate onSubmit={save}>
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            Its pair reported <code>{endpoint?.coreUrl}</code>. That is a broker-to-broker connector, not a management
            URL, so Studio cannot reach it yet. Enter the Jolokia URL you can reach this broker on.
          </Text>
          <TextInput
            label="Management URL"
            placeholder="http://broker-2:8261/console/jolokia"
            {...form.getInputProps('url')}
          />
          <TextInput
            label="Core URL"
            description="Optional. Set this when the advertised connector is not reachable from Studio (needed for live events)."
            placeholder="tcp://broker-2:61617"
            {...form.getInputProps('coreUrl')}
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

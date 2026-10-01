import { useState, type ReactNode } from 'react';
import { Button, PasswordInput, SegmentedControl, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useCluster, useRotateCredentials } from './api.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import classes from './Clusters.module.css';
import { RegisterClusterButton } from './RegisterClusterButton.tsx';

/** Settings section: register another cluster. */
export function RegisterSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Register another cluster; remove this one under This cluster → Remove cluster. Studio finds brokers that join a
        registered cluster on its own.
      </Text>
      <RegisterClusterButton />
    </>
  );
}

type CredentialKind = 'JOLOKIA_BASIC' | 'CORE';

/**
 * Studio holds two broker accounts per cluster: the HTTP Basic one Jolokia
 * management uses, and an optional Core-protocol one. When no Core credential is
 * stored, the Core client falls back to the Jolokia account (ADR-0026, D6) — which
 * fails with `AMQ229099` on a cluster whose management account is also its
 * `<cluster-user>`, because Artemis reserves that account for inter-node traffic.
 * Setting a Core credential here is the fix, and it is why the kind is selectable
 * rather than implied.
 */
const CREDENTIAL_KINDS: Record<CredentialKind, { label: string; hint: string }> = {
  JOLOKIA_BASIC: {
    label: 'Management (Jolokia)',
    hint: 'The HTTP Basic account Studio uses to reach every node’s management endpoint. Also used for the Core protocol when no Core account is stored.',
  },
  CORE: {
    label: 'Core protocol',
    hint: 'A separate broker account for the Core connection (notifications and faithful message I/O). Set this when the management account is the broker’s <cluster-user>, which Artemis refuses to authenticate over Core with AMQ229099.',
  },
};

const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };

function CredentialRotation({ clusterId, clusterName }: Readonly<{ clusterId: string; clusterName: string }>) {
  const rotate = useRotateCredentials(clusterId);
  const [confirming, setConfirming] = useState(false);
  const form = useForm<{ kind: CredentialKind; username: string; password: string }>({
    initialValues: { kind: 'JOLOKIA_BASIC', username: '', password: '' },
    validateInputOnBlur: true,
    // Both fields are validated when the button is pressed, and on leaving a field; nothing is disabled silently.
    validate: {
      username: (v) => (v.trim() ? null : 'Enter the account name.'),
      password: (v) => (v ? null : 'Enter the new password.'),
    },
  });
  const { kind, username } = form.values;
  const label = CREDENTIAL_KINDS[kind].label;

  const submit = form.onSubmit(() => {
    rotate.reset();
    setConfirming(true);
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form className={classes.settingsForm} noValidate onSubmit={submit}>
      <div>
        <Text component="label" size="xs" fw={500} display="block" mb="0.25rem" id="credential-kind">
          Account
        </Text>
        <SegmentedControl
          size="xs"
          fullWidth
          aria-labelledby="credential-kind"
          {...form.getInputProps('kind')}
          onChange={(v) => form.setFieldValue('kind', v === 'CORE' ? 'CORE' : 'JOLOKIA_BASIC')}
          data={(Object.keys(CREDENTIAL_KINDS) as CredentialKind[]).map((k) => ({
            value: k,
            label: CREDENTIAL_KINDS[k].label,
          }))}
        />
        <Text size="xs" c="dimmed" mt="0.25rem">
          {CREDENTIAL_KINDS[kind].hint}
        </Text>
      </div>
      <TextInput label="Username" {...form.getInputProps('username')} size="xs" />
      <PasswordInput label="Password" {...form.getInputProps('password')} size="xs" />
      <Button type="submit" size="xs" className={classes.start}>
        Save {label.toLowerCase()} credentials…
      </Button>
      <Text size="xs" c="dimmed">
        The new secret is AES-GCM sealed and the change is audited. It replaces this cluster’s stored{' '}
        {label.toLowerCase()} account on every node; the other account is left alone.
      </Text>
      <ConfirmDialog
        opened={confirming}
        onClose={() => setConfirming(false)}
        title={`Save ${label.toLowerCase()} credentials`}
        consequence={
          <Stack gap="xs">
            <Text size="sm">
              This replaces the stored {label.toLowerCase()} account of <strong>{clusterName}</strong> on every node
              with <strong>{username}</strong>. The next scrape uses it; if the broker refuses it, this cluster reads as
              unreachable until the account is corrected.
            </Text>
            {rotate.isError ? <ErrorState variant="inline" error={rotate.error} /> : null}
          </Stack>
        }
        confirmLabel={`Save ${label.toLowerCase()} credentials`}
        tone="danger"
        typedName={clusterName}
        pending={rotate.isPending}
        onConfirm={() =>
          rotate.mutate(
            { username: form.values.username, password: form.values.password, kind },
            {
              onSuccess: () => {
                notify.succeeded({ action: SAVE, subject: `${label.toLowerCase()} credentials of ${clusterName}` });
                form.setValues({ username: '', password: '' });
                form.clearErrors();
                setConfirming(false);
              },
            },
          )
        }
      />
    </form>
  );
}

/** A section's content once the cluster has loaded; until then a frame of its size, so nothing below moves. */
function SectionBody({
  cluster,
  blockSize,
  children,
}: Readonly<{
  cluster: ReturnType<typeof useCluster>;
  /** The height of the content that replaces the frame. */
  blockSize: string;
  children: (data: NonNullable<ReturnType<typeof useCluster>['data']>) => ReactNode;
}>) {
  if (cluster.data) return <>{children(cluster.data)}</>;
  if (cluster.isError) return <ErrorState error={cluster.error} onRetry={() => void cluster.refetch()} />;
  return <LoadingState label="Loading the cluster" blockSize={blockSize} />;
}

/** Settings section: rotate the broker accounts Studio uses for this cluster. */
export function CredentialsSection({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        The accounts Studio uses to reach every node of <strong>{cluster.data?.name ?? 'this cluster'}</strong>.
        Management and Core are stored separately, so a cluster whose management account is its{' '}
        <code>&lt;cluster-user&gt;</code> can still open a Core connection.
      </Text>
      <SectionBody cluster={cluster} blockSize="18rem">
        {(data) => <CredentialRotation clusterId={clusterId} clusterName={data.name} />}
      </SectionBody>
    </>
  );
}

/** Settings section: what this connection can and cannot do. */
export function CapabilitiesSection({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        What this connection can and cannot do over Jolokia. Rows that are not plainly available expand with the reason
        and the exact <code>broker.xml</code> change to close the gap.
      </Text>
      <SectionBody cluster={cluster} blockSize="14rem">
        {(data) => <CapabilityLedger capabilities={data.capabilities} clusterId={clusterId} />}
      </SectionBody>
    </>
  );
}

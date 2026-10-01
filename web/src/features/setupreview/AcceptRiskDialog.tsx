import { Button, Group, Modal, Select, Stack, Text, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';

import { serverNow } from '../../kernel/time/time.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useAcceptRisk, type SetupFindingView } from './api.ts';

const ACCEPT: ActionVerb = { verb: 'Accept', past: 'Accepted', progressive: 'Accepting' };

const EXPIRY = [
  { value: '7', label: 'For 7 days' },
  { value: '30', label: 'For 30 days' },
  { value: '90', label: 'For 90 days' },
  { value: 'never', label: 'Until revoked' },
];

/**
 * Accepts a finding as a known risk (ADR-0106). A reason is required — the audit trail and the
 * next operator both need to know why — and an expiry is the default, so an acceptance made for
 * a migration does not silence the risk forever.
 */
export function AcceptRiskDialog({
  clusterId,
  finding,
  onClose,
}: Readonly<{
  clusterId: string;
  finding: SetupFindingView | null;
  onClose: () => void;
}>) {
  return (
    <Modal opened={finding !== null} onClose={onClose} title="Accept as a known risk" size="lg">
      {finding ? (
        <Form key={`${finding.code}|${finding.subject}`} clusterId={clusterId} finding={finding} onClose={onClose} />
      ) : null}
    </Modal>
  );
}

function Form({
  clusterId,
  finding,
  onClose,
}: Readonly<{
  clusterId: string;
  finding: SetupFindingView;
  onClose: () => void;
}>) {
  const accept = useAcceptRisk(clusterId);
  const form = useForm({
    initialValues: { reason: '', expiry: '30' },
    validateInputOnBlur: true,
    validate: { reason: (v) => (v.trim() ? null : 'A reason is required.') },
  });

  const submit = form.onSubmit(({ reason, expiry }) => {
    const expiresAt =
      expiry === 'never' ? null : new Date(serverNow() + Number(expiry) * 24 * 3600 * 1000).toISOString();
    accept.mutate(
      { code: finding.code, subject: finding.subject, reason: reason.trim(), expiresAt },
      {
        onSuccess: () => {
          notify.succeeded({ action: ACCEPT, subject: `${finding.code} as a known risk` });
          onClose();
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap="sm">
        <Text size="sm" fw={600}>
          {finding.title}
        </Text>
        <Text size="sm">
          The finding stays on this screen, marked accepted. A setup-risk alert for it resolves and does not fire again
          until the acceptance expires or is revoked. Who accepted it, and why, is recorded in the audit trail.
        </Text>
        <Textarea
          label="Reason"
          description="Why this is acceptable here, e.g. “development cluster, no production traffic”."
          {...form.getInputProps('reason')}
          autosize
          minRows={2}
          maxLength={1000}
          required
          data-autofocus
        />
        <Select label="Accepted" data={EXPIRY} {...form.getInputProps('expiry')} allowDeselect={false} />
        {accept.isError ? <ErrorState variant="inline" error={accept.error} /> : null}
        <Group justify="flex-end">
          <Button variant="subtle" onClick={onClose} disabled={accept.isPending}>
            Cancel
          </Button>
          <Button type="submit" loading={accept.isPending}>
            Accept the risk
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

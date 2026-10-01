import { useState } from 'react';
import { useRef } from 'react';
import { Button, Group, Modal, Select, Stack, Text, Textarea } from '@mantine/core';

import { serverNow } from '../../kernel/time/time.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
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
  const [reason, setReason] = useState('');
  const [expiry, setExpiry] = useState<string>('30');
  const [error, setError] = useState<string | null>(null);
  const reasonInput = useRef<HTMLTextAreaElement>(null);

  const submit = () => {
    if (!reason.trim()) {
      setError('A reason is required.');
      reasonInput.current?.focus();
      return;
    }
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
  };

  return (
    <Stack gap="sm">
      <Text size="sm" fw={600}>
        {finding.title}
      </Text>
      <Text size="sm">
        The finding stays on this screen, marked accepted. A setup-risk alert for it resolves and does not fire again
        until the acceptance expires or is revoked. Who accepted it, and why, is recorded in the audit trail.
      </Text>
      <Textarea
        ref={reasonInput}
        label="Reason"
        description="Why this is acceptable here, e.g. “development cluster, no production traffic”."
        value={reason}
        onChange={(e) => {
          setReason(e.currentTarget.value);
          if (error) setError(null);
        }}
        onBlur={() => setError(reason.trim() ? null : 'A reason is required.')}
        error={error}
        autosize
        minRows={2}
        maxLength={1000}
        required
        data-autofocus
      />
      <Select label="Accepted" data={EXPIRY} value={expiry} onChange={(v) => v && setExpiry(v)} allowDeselect={false} />
      {accept.isError ? <ErrorState variant="inline" error={accept.error} /> : null}
      <Group justify="flex-end">
        <Button variant="subtle" onClick={onClose} disabled={accept.isPending}>
          Cancel
        </Button>
        <Button onClick={submit} loading={accept.isPending}>
          Accept the risk
        </Button>
      </Group>
    </Stack>
  );
}

import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { Button, Group, Stack, Text, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { needsReauthentication } from '../auth/api.ts';
import { useFreshSignIn } from '../auth/freshSignIn.ts';
import { StepUpPrompt } from '../auth/StepUp.tsx';
import { problemSlug, useDecide, type HeldDecision, type HeldOperationDetail } from './api.ts';
import { Recap } from './Recap.tsx';

const APPROVE: ActionVerb = { verb: 'Approve', past: 'Approved', progressive: 'Approving' };
const REJECT: ActionVerb = { verb: 'Reject', past: 'Rejected', progressive: 'Rejecting' };

/** The longest reason the server keeps. */
const REASON_MAX = 500;

type Vote = HeldDecision['vote'];

/** Why the panel stopped a decision before it was made: the request moved under the decider. */
type Stale = { kind: 'changed' | 'closed'; detail: string };

/**
 * Studio's own decision controls, for anyone the server says may decide: a reason, then Approve or Reject, each
 * confirmed in a dialog that says once more what is being approved. The vote is bound to the parameters and
 * version shown, so a request that changed meanwhile is refused and shown again rather than decided blind. A
 * stale sign-in is confirmed in place, and the vote is sent again once it is.
 */
export function DecisionPanel({ detail, refresh }: Readonly<{ detail: HeldOperationDetail; refresh: () => void }>) {
  const { operation } = detail;
  const decide = useDecide(operation.id);
  const fresh = useFreshSignIn();
  const form = useForm({
    initialValues: { reason: '' },
    validateInputOnBlur: true,
    validate: {
      reason: (v) => (v.length > REASON_MAX ? `Keep the reason to ${REASON_MAX} characters.` : null),
    },
  });
  // What the dialog confirms, captured when it opened: a refetch behind it never changes what is sent.
  const [pending, setPending] = useState<HeldDecision | null>(null);
  const [stale, setStale] = useState<Stale | null>(null);

  const { mutate, error, reset } = decide;
  const send = (body: HeldDecision) => {
    const action = body.vote === 'APPROVE' ? APPROVE : REJECT;
    mutate(body, {
      onSuccess: () => {
        setPending(null);
        form.reset();
        notify.succeeded({ action, subject: `request "${operation.summary}"` });
      },
      onError: (e) => {
        const slug = problemSlug(e);
        if (slug === 'held-operation-changed' || slug === 'held-operation-closed') {
          setPending(null);
          setStale({ kind: slug === 'held-operation-changed' ? 'changed' : 'closed', detail: e.message });
          refresh();
        } else if (slug === 'decision-reason-required') {
          setPending(null);
          form.setFieldError('reason', e.message || 'Give a reason for this decision.');
          form.getInputNode('reason')?.focus();
        }
        // Anything else stays in the dialog: a stale sign-in as the step-up, a refusal as its reason.
      },
    });
  };

  // After a step-up the 403 is out of date: send the same vote once more when the session turns fresh.
  const retry = useEffectEvent(() => {
    if (pending && needsReauthentication(error)) send(pending);
  });
  const wasFresh = useRef(fresh);
  useEffect(() => {
    if (fresh && !wasFresh.current) retry();
    wasFresh.current = fresh;
  }, [fresh]);

  if (operation.state !== 'HELD') return null;

  if (!detail.canDecide) {
    return (
      <Notice title={detail.mine ? 'Your own request' : 'You cannot decide this request'} tone="neutral">
        {detail.decideRefusal ??
          (detail.mine
            ? 'Someone else must approve it. You can cancel it while it waits.'
            : 'Only an approver the policy names can decide it.')}
      </Notice>
    );
  }

  const open = (vote: Vote) => {
    const reason = form.values.reason.trim();
    if (form.validate().hasErrors) {
      form.getInputNode('reason')?.focus();
      return;
    }
    if (vote === 'REJECT' && !reason) {
      form.setFieldError('reason', 'Give a reason to reject: the requester reads it.');
      form.getInputNode('reason')?.focus();
      return;
    }
    reset();
    setStale(null);
    setPending({ vote, reason: reason || null, paramsHash: detail.paramsHash, version: detail.version });
  };

  const approving = pending?.vote === 'APPROVE';
  const destructive = detail.traits.includes('DESTRUCTIVE');
  const refused = error !== null && !needsReauthentication(error);
  const runs =
    detail.mode === 'BY_REQUESTER'
      ? `${operation.requesterUsername} can then run it themselves, once, within the time allowed.`
      : `Studio runs it once, as ${operation.requesterUsername} asked, as soon as you confirm.`;

  return (
    <Stack gap="sm">
      {stale ? (
        <Notice
          tone={stale.kind === 'changed' ? 'warning' : 'neutral'}
          title={stale.kind === 'changed' ? 'The request changed' : 'Already closed'}
        >
          {stale.kind === 'changed'
            ? 'It changed while you were reviewing it, so nothing was decided. Read it again above, then decide.'
            : `${stale.detail} Nothing was decided.`}
        </Notice>
      ) : null}
      <Textarea
        label="Reason"
        description="Required to reject, optional to approve. The requester reads it, and it is kept in the audit log."
        autosize
        minRows={2}
        maxRows={6}
        maxLength={REASON_MAX}
        {...form.getInputProps('reason')}
      />
      <Group gap="xs">
        <Button onClick={() => open('APPROVE')}>Approve…</Button>
        <Button variant="default" onClick={() => open('REJECT')}>
          Reject…
        </Button>
      </Group>
      <ConfirmDialog
        opened={pending !== null}
        onClose={() => setPending(null)}
        title={approving ? 'Approve this request?' : 'Reject this request?'}
        tone={approving && destructive ? 'danger' : 'default'}
        confirmLabel={approving ? 'Approve' : 'Reject'}
        pending={decide.isPending}
        consequence={
          <Stack gap="sm">
            <Recap detail={detail} reason={pending?.reason ?? null} />
            <Text size="sm">
              {approving ? runs : `It will not run. ${operation.requesterUsername} is told, with your reason.`}
            </Text>
            <StepUpPrompt error={error} returnTo={`${globalThis.location.pathname}${globalThis.location.search}`} />
            {refused ? <ErrorState variant="inline" error={error} /> : null}
          </Stack>
        }
        onConfirm={() => pending && send(pending)}
      />
    </Stack>
  );
}

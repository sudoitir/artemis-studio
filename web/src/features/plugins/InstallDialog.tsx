import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, CopyButton, Group, List, Loader, Modal, Stack, Stepper, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';

import { request } from '../../kernel/api/request.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import {
  keys,
  useActivateUpload,
  useDiscardUpload,
  useDownloadUpdate,
  useUpload,
  violationsOf,
  type PluginPlanView,
  type PluginUploadView,
  type PluginViolationView,
} from './api.ts';
import { ActivationProgress, type Outcome } from './ActivationProgress.tsx';
import { Acknowledgement, PlanReview } from './PlanReview.tsx';
import { TrustKeyDialog } from './TrustKeyDialog.tsx';
import { useFreshSignIn } from '../../kernel/auth/freshSignIn.ts';
import { needsReauthentication } from '../../kernel/auth/api.ts';
import { StepUp } from '../../kernel/auth/StepUp.tsx';
import { actionLabel } from './words.ts';

/** Where the jar comes from: a file the operator chose, a plugin's update URL, or an upload already inspected. */
export type Source = { kind: 'file'; file: File } | { kind: 'update'; id: string } | { kind: 'resume'; sha: string };

function report(violations: PluginViolationView[]): string {
  return violations
    .map((v) => {
      const fix = v.fix ? `\n  Fix: ${v.fix}` : '';
      return `- [${v.code}] ${v.message}${fix}`;
    })
    .join('\n');
}

function sizeOf(file: File): string {
  return file.size >= 1024 * 1024 ? `${(file.size / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(file.size / 1024)} KB`;
}

/** What the inspection is doing, in words; each source is read differently. */
function inspectingWords(source: Source | null): string {
  if (source?.kind === 'file') {
    return `Inspecting ${source.file.name} (${sizeOf(source.file)}): its descriptor, its classes and its database changes. No code in it runs.`;
  }
  if (source?.kind === 'update') {
    return 'Downloading the update and checking it matches the checksum its vendor published.';
  }
  return 'Reading the upload again.';
}

/** The first step: while the jar is checked, and everything wrong with it at once if it cannot be installed. */
function InspectStep({
  source,
  inspecting,
  error,
  violations,
}: Readonly<{
  source: Source | null;
  inspecting: boolean;
  error: Error | null;
  violations: PluginViolationView[];
}>) {
  return (
    <Stack gap="sm" mt="md">
      {inspecting ? (
        <Text size="sm" aria-live="polite">
          <Loader size="xs" mr={6} />
          {inspectingWords(source)}
        </Text>
      ) : null}
      {error ? (
        <Alert variant="light" color="red" title="This jar cannot be installed" role="alert">
          <Stack gap="xs">
            {violations.length > 0 ? (
              <List size="sm" spacing={4}>
                {violations.map((v) => (
                  <List.Item key={v.code + v.message}>
                    {v.message}
                    {v.fix ? (
                      <Text size="xs" c="dimmed">
                        {v.fix}
                      </Text>
                    ) : null}
                  </List.Item>
                ))}
              </List>
            ) : (
              <Text size="sm">{error.message}</Text>
            )}
            {violations.length > 0 ? (
              <CopyButton value={report(violations)}>
                {({ copied, copy }) => (
                  <Button size="xs" variant="default" w="fit-content" onClick={copy}>
                    {copied ? 'Copied' : 'Copy report for the plugin author'}
                  </Button>
                )}
              </CopyButton>
            ) : null}
            <Text size="sm">Nothing was stored.</Text>
          </Stack>
        </Alert>
      ) : null}
    </Stack>
  );
}

/** Who the change reaches: it replaces a version, or adds the plugin, for everyone. */
function scopeWords(plan: PluginPlanView): string {
  return plan.fromVersion
    ? `Replaces ${plan.info.title} ${plan.fromVersion} for everyone using Studio.`
    : `Adds ${plan.info.title} for everyone using Studio.`;
}

/** What confirming interrupts, if anything. */
function interruptionWords(plan: PluginPlanView): string {
  if (plan.activationClass === 'RESTART' && plan.restart === 'AUTOMATIC') {
    return 'Studio restarts, disconnecting everyone briefly.';
  }
  if (plan.activationClass === 'BRIEF_MAINTENANCE' && plan.fromVersion)
    return `${plan.info.title} pauses for a few seconds.`;
  return 'Nobody is interrupted.';
}

/** Why Continue is off: an unsigned plugin the installation refuses, or a key nobody has trusted yet. */
function blockedWords(plan: PluginPlanView): string {
  return plan.trust.status === 'UNSIGNED'
    ? 'Continue is unavailable while unverified plugins are not allowed.'
    : 'Continue is unavailable until its publisher is trusted.';
}

/** Cancel and Continue; Continue stays off, with its reason beside it, until the publisher is allowed. */
function ReviewActions({
  plan,
  onCancel,
  onContinue,
}: Readonly<{ plan: PluginPlanView; onCancel: () => void; onContinue: () => void }>) {
  const allowed = plan.trust.allowed;
  return (
    <Group justify="flex-end">
      {allowed ? null : (
        <Text size="xs" c="dimmed">
          {blockedWords(plan)}
        </Text>
      )}
      <Button variant="default" onClick={onCancel}>
        Cancel
      </Button>
      <Button disabled={!allowed} onClick={onContinue}>
        Continue
      </Button>
    </Group>
  );
}

/** The third step: the blast radius, a fresh sign-in, and the plugin's id typed. */
function ConfirmStep({
  plan,
  sha,
  returnTo,
  activate,
  fresh,
  onActivated,
}: Readonly<{
  plan: PluginPlanView;
  sha: string;
  returnTo: string;
  activate: ReturnType<typeof useActivateUpload>;
  fresh: boolean;
  onActivated: () => void;
}>) {
  const [acknowledged, setAcknowledged] = useState(false);
  const needsAcknowledgement = plan.acknowledgements.length > 0;
  const unacknowledged = needsAcknowledgement && !acknowledged;
  return (
    <Stack gap="md" mt="md">
      <Text size="sm">
        {scopeWords(plan)} {interruptionWords(plan)}
      </Text>
      {plan.missingRequires.length > 0 ? (
        <Text size="sm" c="red">
          It requires {plan.missingRequires.join(', ')} first.
        </Text>
      ) : null}
      {needsAcknowledgement ? <Acknowledgement plan={plan} checked={acknowledged} onChange={setAcknowledged} /> : null}
      <StepUp returnTo={returnTo} />
      {activate.error && !needsReauthentication(activate.error) ? (
        <Alert variant="light" color="red" title="Not activated" role="alert">
          {violationsOf(activate.error)
            .map((v) => v.message)
            .join(' ') || activate.error.message}
        </Alert>
      ) : null}
      <ConfirmByTyping
        token={plan.pluginId}
        label={`Type "${plan.pluginId}" to confirm`}
        confirmLabel={actionLabel(plan)}
        color="blue"
        loading={activate.isPending}
        disabled={!fresh || plan.missingRequires.length > 0 || unacknowledged}
        onConfirm={() => activate.mutate({ sha, acknowledge: acknowledged }, { onSuccess: onActivated })}
      />
      {unacknowledged ? (
        <Text size="xs" c="dimmed">
          Tick the confirmation above to activate.
        </Text>
      ) : null}
      {!fresh ? (
        <Text size="xs" c="dimmed">
          Confirm it is you above first.
        </Text>
      ) : null}
    </Stack>
  );
}

/**
 * Install or update, in four steps (design.md §8): Inspect — the server validates the jar and
 * says everything wrong with it at once; Review — what it will be able to do, and what
 * confirming interrupts; Confirm — the blast radius, a fresh sign-in, and the plugin's id typed;
 * Progress — the activation as it runs. Nothing is installed before Confirm, and closing the
 * dialog at Progress does not stop anything.
 */
export function InstallDialog({
  source,
  canInstall,
  onClose,
}: Readonly<{ source: Source | null; canInstall: boolean; onClose: () => void }>) {
  const [step, setStep] = useState(0);
  const [inspected, setInspected] = useState<PluginUploadView | null>(null);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [outcome, setOutcome] = useState<Outcome>('pending');
  const [trusting, setTrusting] = useState(false);
  const upload = useUpload();
  const download = useDownloadUpdate();
  const activate = useActivateUpload();
  const discard = useDiscardUpload();
  const fresh = useFreshSignIn();
  const started = useRef<Source | null>(null);

  const resumed = useQuery({
    queryKey: keys.upload(source?.kind === 'resume' ? source.sha : ''),
    queryFn: () => request<PluginPlanView>(`/admin/plugins/uploads/${(source as { sha: string }).sha}`),
    enabled: source?.kind === 'resume',
    retry: false,
  });

  // Inspect as soon as there is something to inspect, once per source.
  useEffect(() => {
    if (!source || started.current === source) return;
    started.current = source;
    setStep(0);
    setInspected(null);
    setStartedAt(null);
    setOutcome('pending');
    const done = (view: PluginUploadView) => {
      setInspected(view);
      setStep(1);
    };
    if (source.kind === 'file') upload.mutate(source.file, { onSuccess: done });
    if (source.kind === 'update') download.mutate(source.id, { onSuccess: done });
  }, [source, upload, download]);

  useEffect(() => {
    if (source?.kind === 'resume' && resumed.data && !inspected) {
      setInspected({ sha256: source.sha, plan: resumed.data, warnings: [] });
      // A publisher nobody trusts yet is settled in Review, so a resume lands there.
      setStep(resumed.data.trust.allowed ? 2 : 1);
    }
  }, [source, resumed.data, inspected]);

  const plan = inspected?.plan;
  const inspecting = upload.isPending || download.isPending || resumed.isPending;
  const inspectError = upload.error ?? download.error ?? resumed.error;
  const violations = violationsOf(inspectError);
  const onOutcome = useCallback((o: Outcome) => setOutcome(o), []);

  const close = () => {
    // An inspected upload nobody activated is forgotten now rather than in a day.
    if (inspected && startedAt === null && step < 3) discard.mutate(inspected.sha256);
    started.current = null;
    upload.reset();
    download.reset();
    activate.reset();
    onClose();
  };

  // After a key is trusted the same jar is planned again: its status, and what needs confirming, change.
  const replan = async () => {
    if (!inspected) return;
    const fresh = await request<PluginPlanView>(`/admin/plugins/uploads/${inspected.sha256}`);
    setInspected({ ...inspected, plan: fresh });
  };

  const uploadQuery = inspected ? `&upload=${inspected.sha256}` : '';
  const returnTo = `${globalThis.location.pathname}?tab=plugins${uploadQuery}`;

  return (
    <Modal
      opened={source !== null}
      onClose={close}
      size={920}
      title={plan ? actionLabel(plan) : 'Install plugin'}
      closeOnClickOutside={step < 2}
      // One Escape closes one layer: while the key dialog is open it closes that, not this.
      closeOnEscape={!trusting}
    >
      <Stepper active={step} size="sm" allowNextStepsSelect={false}>
        <Stepper.Step label="Inspect" description="Checked before it is stored">
          <InspectStep source={source} inspecting={inspecting} error={inspectError} violations={violations} />
        </Stepper.Step>

        <Stepper.Step label="Review" description="What it will do">
          {plan ? (
            <Stack gap="md" mt="md">
              <PlanReview
                plan={plan}
                warnings={inspected?.warnings}
                trust={{ canInstall, onTrust: () => setTrusting(true) }}
              />
              <ReviewActions plan={plan} onCancel={close} onContinue={() => setStep(2)} />
            </Stack>
          ) : null}
        </Stepper.Step>

        <Stepper.Step label="Confirm" description="Who and what">
          {plan && inspected ? (
            <ConfirmStep
              plan={plan}
              sha={inspected.sha256}
              returnTo={returnTo}
              activate={activate}
              fresh={fresh}
              onActivated={() => {
                setStartedAt(Date.now());
                setStep(3);
              }}
            />
          ) : null}
        </Stepper.Step>

        <Stepper.Step label="Progress" description="Carries on if you close this">
          {plan && startedAt !== null ? (
            <Stack gap="md" mt="md">
              <ActivationProgress plan={plan} startedAt={startedAt} onOutcome={onOutcome} />
              <Group justify="flex-end">
                <Button variant={outcome === 'pending' ? 'default' : 'filled'} onClick={close}>
                  {outcome === 'pending' ? 'Close — it carries on' : 'Done'}
                </Button>
              </Group>
            </Stack>
          ) : null}
        </Stepper.Step>
      </Stepper>
      {plan && inspected ? (
        <TrustKeyDialog
          opened={trusting}
          onClose={() => setTrusting(false)}
          sha256={inspected.sha256}
          trust={plan.trust}
          returnTo={returnTo}
          onTrusted={replan}
        />
      ) : null}
    </Modal>
  );
}

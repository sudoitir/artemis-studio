import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, CopyButton, Group, List, Loader, Modal, Stack, Stepper, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';

import { request } from '../../kernel/api/request.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import {
  keys,
  needsReauthentication,
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
import { PlanReview } from './PlanReview.tsx';
import { useFreshSignIn } from './freshSignIn.ts';
import { StepUp } from './StepUp.tsx';
import { actionLabel } from './words.ts';

/** Where the jar comes from: a file the operator chose, a plugin's update URL, or an upload already inspected. */
export type Source = { kind: 'file'; file: File } | { kind: 'update'; id: string } | { kind: 'resume'; sha: string };

function report(violations: PluginViolationView[]): string {
  return violations.map((v) => `- [${v.code}] ${v.message}${v.fix ? `\n  Fix: ${v.fix}` : ''}`).join('\n');
}

function sizeOf(file: File): string {
  return file.size >= 1024 * 1024 ? `${(file.size / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(file.size / 1024)} KB`;
}

/**
 * Install or update, in four steps (design.md §8): Inspect — the server validates the jar and
 * says everything wrong with it at once; Review — what it will be able to do, and what
 * confirming interrupts; Confirm — the blast radius, a fresh sign-in, and the plugin's id typed;
 * Progress — the activation as it runs. Nothing is installed before Confirm, and closing the
 * dialog at Progress does not stop anything.
 */
export function InstallDialog({ source, onClose }: { source: Source | null; onClose: () => void }) {
  const [step, setStep] = useState(0);
  const [inspected, setInspected] = useState<PluginUploadView | null>(null);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [outcome, setOutcome] = useState<Outcome>('pending');
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
      setStep(2);
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

  const returnTo = `${window.location.pathname}?tab=plugins${inspected ? `&upload=${inspected.sha256}` : ''}`;

  return (
    <Modal
      opened={source !== null}
      onClose={close}
      size={920}
      title={plan ? actionLabel(plan) : 'Install plugin'}
      closeOnClickOutside={step < 2}
    >
      <Stepper active={step} size="sm" allowNextStepsSelect={false}>
        <Stepper.Step label="Inspect" description="Checked before it is stored">
          <Stack gap="sm" mt="md">
            {inspecting ? (
              <Text size="sm" aria-live="polite">
                <Loader size="xs" mr={6} />
                {source?.kind === 'file'
                  ? `Inspecting ${source.file.name} (${sizeOf(source.file)}): its descriptor, its classes and its database changes. No code in it runs.`
                  : source?.kind === 'update'
                    ? 'Downloading the update and checking it matches the checksum its vendor published.'
                    : 'Reading the upload again.'}
              </Text>
            ) : null}
            {inspectError ? (
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
                    <Text size="sm">{inspectError.message}</Text>
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
        </Stepper.Step>

        <Stepper.Step label="Review" description="What it will do">
          {plan ? (
            <Stack gap="md" mt="md">
              <PlanReview plan={plan} warnings={inspected?.warnings} />
              <Group justify="flex-end">
                <Button variant="default" onClick={close}>
                  Cancel
                </Button>
                <Button onClick={() => setStep(2)}>Continue</Button>
              </Group>
            </Stack>
          ) : null}
        </Stepper.Step>

        <Stepper.Step label="Confirm" description="Who and what">
          {plan && inspected ? (
            <Stack gap="md" mt="md">
              <Text size="sm">
                {plan.fromVersion ? `Replaces ${plan.info.title} ${plan.fromVersion} for everyone using Studio.` : `Adds ${plan.info.title} for everyone using Studio.`}{' '}
                {plan.activationClass === 'RESTART' && plan.restart === 'AUTOMATIC'
                  ? 'Studio restarts, disconnecting everyone briefly.'
                  : plan.activationClass === 'BRIEF_MAINTENANCE' && plan.fromVersion
                    ? `${plan.info.title} pauses for a few seconds.`
                    : 'Nobody is interrupted.'}
              </Text>
              {plan.missingRequires.length > 0 ? (
                <Text size="sm" c="red">
                  It requires {plan.missingRequires.join(', ')} first.
                </Text>
              ) : null}
              <StepUp returnTo={returnTo} />
              {activate.error && !needsReauthentication(activate.error) ? (
                <Alert variant="light" color="red" title="Not activated" role="alert">
                  {violationsOf(activate.error).map((v) => v.message).join(' ') || activate.error.message}
                </Alert>
              ) : null}
              <ConfirmByTyping
                token={plan.pluginId}
                label={`Type "${plan.pluginId}" to confirm`}
                confirmLabel={actionLabel(plan)}
                color="blue"
                loading={activate.isPending}
                disabled={!fresh || plan.missingRequires.length > 0}
                onConfirm={() =>
                  activate.mutate(inspected.sha256, {
                    onSuccess: () => {
                      setStartedAt(Date.now());
                      setStep(3);
                    },
                  })
                }
              />
              {!fresh ? (
                <Text size="xs" c="dimmed">
                  Confirm it is you above first.
                </Text>
              ) : null}
            </Stack>
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
    </Modal>
  );
}

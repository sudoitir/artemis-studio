import { useEffect, useState } from 'react';
import { Button, Code, Stack, Text, Timeline } from '@mantine/core';
import { IconCheck } from '@tabler/icons-react';

import { LoadingState } from '../../ui/LoadingState.tsx';
import type { PluginPlanView } from './api.ts';
import { Notice } from '../../ui/Notice.tsx';
import styles from './Plugins.module.css';
import { usePlugins } from './api.ts';
import { STEPS } from './words.ts';

/** Seconds since `from`, ticking, for the step in flight. */
function useElapsed(from: string | null | undefined): number | null {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!from) return;
    const timer = globalThis.setInterval(() => setNow(Date.now()), 1_000);
    return () => globalThis.clearInterval(timer);
  }, [from]);
  return from ? Math.max(0, Math.round((now - new Date(from).getTime()) / 1000)) : null;
}

export type Outcome = 'pending' | 'succeeded' | 'failed' | 'restart';

/** How the activation stands: active since it began, failed, waiting on a restart, or still going. */
function outcomeOf(status: string | undefined, activatedSince: boolean, restarting: boolean): Outcome {
  if (status === 'active' && activatedSince) return 'succeeded';
  if (status === 'failed') return 'failed';
  return status === 'needs_restart' || restarting ? 'restart' : 'pending';
}

/** What each outcome says, and the next action it leaves the operator. */
function OutcomeNote({
  outcome,
  plan,
  failure,
  restart,
  serverDown,
}: Readonly<{
  outcome: Outcome;
  plan: PluginPlanView;
  failure: string | null | undefined;
  restart: { supervised?: boolean; command?: string | null } | undefined;
  serverDown: boolean;
}>) {
  const title = plan.info.title;
  if (outcome === 'pending') {
    return (
      <div className={styles.progressLine}>
        <LoadingState variant="inline" label={`Activating ${title}`} />
        <Text size="sm">
          Activating {title} {plan.toVersion}. You can close this; it carries on.
        </Text>
      </div>
    );
  }
  if (outcome === 'succeeded') {
    return (
      <Notice title={`${title} ${plan.toVersion} is active`} tone="info">
        <Stack gap="xs">
          {plan.info.contributions.ui ? (
            <>
              <Text size="sm">Reload Studio to load its screens.</Text>
              <Button className={styles.start} onClick={() => globalThis.location.reload()}>
                Reload Studio
              </Button>
            </>
          ) : (
            <Text size="sm">It is running; it has no screens of its own.</Text>
          )}
        </Stack>
      </Notice>
    );
  }
  if (outcome === 'failed') {
    return (
      <Notice title={`${title} ${plan.toVersion} did not start`} tone="danger">
        <Stack gap="xs">
          <Text size="sm">{failure ?? 'The server gave no reason.'}</Text>
          {plan.fromVersion && plan.activationClass === 'INSTANT' ? (
            <Text size="sm">{plan.fromVersion} is still running; nothing changed for its users.</Text>
          ) : null}
          <Text size="sm">Upload a fixed version, or open the plugin's details to retry or remove it.</Text>
        </Stack>
      </Notice>
    );
  }
  if (restart?.supervised || serverDown) {
    return (
      <Notice title="Studio is restarting" tone="info">
        <div className={styles.progressLine}>
          <LoadingState variant="inline" label="Waiting for Studio to come back" />
          <Text size="sm">
            {title} starts with it. This page reconnects by itself; everyone is disconnected until Studio is back,
            usually under a minute.
          </Text>
        </div>
      </Notice>
    );
  }
  return (
    <Notice title={`${title} starts when Studio restarts`} tone="warning">
      <Stack gap="xs">
        <Text size="sm">Studio cannot restart itself where it runs. Restart it with:</Text>
        <Code block>{restart?.command ?? 'docker compose restart studio'}</Code>
      </Stack>
    </Notice>
  );
}

/**
 * Where an activation has got to, and how it ended (design.md §8), from the plugin's own
 * status — polled every second while it runs. Closing the dialog never stops it; this is only a
 * view. Four outcomes, each with the next action: active; failed, with the cause and what is still
 * running; waiting for a restart Studio makes itself; waiting for one the operator must make.
 */
export function ActivationProgress({
  plan,
  startedAt,
  onOutcome,
}: Readonly<{
  plan: PluginPlanView;
  startedAt: number;
  onOutcome?: (outcome: Outcome) => void;
}>) {
  const plugins = usePlugins();
  const plugin = plugins.data?.plugins.find((p) => p.id === plan.pluginId);
  const restart = plugins.data?.restart;
  const elapsed = useElapsed(plugin?.stepStartedAt);
  const serverDown = plugins.isError && plugins.failureCount > 0;

  const activatedSince =
    plugin?.activatedAt !== undefined && plugin?.activatedAt !== null
      ? new Date(plugin.activatedAt).getTime() >= startedAt - 5_000
      : false;
  const outcome = outcomeOf(plugin?.status, activatedSince, Boolean(restart?.restarting) || serverDown);

  useEffect(() => onOutcome?.(outcome), [outcome, onOutcome]);

  const steps = STEPS.filter((s) => s.key !== 'draining' || plan.activationClass === 'BRIEF_MAINTENANCE');
  const current = steps.findIndex((s) => s.key === plugin?.progress);

  return (
    <Stack gap="md">
      <Timeline
        active={outcome === 'succeeded' ? steps.length : Math.max(current, 0)}
        bulletSize="1.125rem"
        lineWidth="0.125rem"
      >
        {steps.map((step, index) => (
          <Timeline.Item
            key={step.key}
            title={step.label}
            bullet={index < current || outcome === 'succeeded' ? <IconCheck size="0.75rem" aria-hidden /> : undefined}
          >
            {index === current && outcome === 'pending' ? (
              <Text size="xs" c="dimmed">
                in progress{elapsed !== null ? ` · ${elapsed} s` : ''}
              </Text>
            ) : null}
          </Timeline.Item>
        ))}
      </Timeline>

      <div aria-live="polite">
        <OutcomeNote
          outcome={outcome}
          plan={plan}
          failure={plugin?.failure}
          restart={restart}
          serverDown={serverDown}
        />
      </div>
    </Stack>
  );
}

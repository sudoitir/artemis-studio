import { useEffect, useState } from 'react';
import { Alert, Button, Code, Loader, Stack, Text, Timeline } from '@mantine/core';
import { IconCheck } from '@tabler/icons-react';

import type { PluginPlanView } from './api.ts';
import { usePlugins } from './api.ts';
import { STEPS } from './words.ts';

/** Seconds since `from`, ticking, for the step in flight. */
function useElapsed(from: string | null | undefined): number | null {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!from) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [from]);
  return from ? Math.max(0, Math.round((now - new Date(from).getTime()) / 1000)) : null;
}

export type Outcome = 'pending' | 'succeeded' | 'failed' | 'restart';

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
}: {
  plan: PluginPlanView;
  startedAt: number;
  onOutcome?: (outcome: Outcome) => void;
}) {
  const plugins = usePlugins();
  const plugin = plugins.data?.plugins.find((p) => p.id === plan.pluginId);
  const restart = plugins.data?.restart;
  const elapsed = useElapsed(plugin?.stepStartedAt);
  const serverDown = plugins.isError && plugins.failureCount > 0;

  const activatedSince =
    plugin?.activatedAt !== undefined && plugin?.activatedAt !== null
      ? new Date(plugin.activatedAt).getTime() >= startedAt - 5_000
      : false;
  const outcome: Outcome =
    plugin?.status === 'active' && activatedSince
      ? 'succeeded'
      : plugin?.status === 'failed'
        ? 'failed'
        : plugin?.status === 'needs_restart' || restart?.restarting || serverDown
          ? 'restart'
          : 'pending';

  useEffect(() => onOutcome?.(outcome), [outcome, onOutcome]);

  const steps = STEPS.filter((s) => s.key !== 'draining' || plan.activationClass === 'BRIEF_MAINTENANCE');
  const current = steps.findIndex((s) => s.key === plugin?.progress);
  const title = plan.info.title;

  return (
    <Stack gap="md">
      <Timeline active={outcome === 'succeeded' ? steps.length : Math.max(current, 0)} bulletSize={18} lineWidth={2}>
        {steps.map((step, index) => (
          <Timeline.Item
            key={step.key}
            title={step.label}
            bullet={index < current || outcome === 'succeeded' ? <IconCheck size={12} aria-hidden /> : undefined}
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
        {outcome === 'pending' ? (
          <Text size="sm">
            <Loader size="xs" mr={6} />
            Activating {title} {plan.toVersion}. You can close this; it carries on.
          </Text>
        ) : null}
        {outcome === 'succeeded' ? (
          <Alert variant="light" title={`${title} ${plan.toVersion} is active`}>
            <Stack gap="xs">
              {plan.info.contributions.ui ? (
                <>
                  <Text size="sm">Reload Studio to load its screens.</Text>
                  <Button w="fit-content" onClick={() => window.location.reload()}>
                    Reload Studio
                  </Button>
                </>
              ) : (
                <Text size="sm">It is running; it has no screens of its own.</Text>
              )}
            </Stack>
          </Alert>
        ) : null}
        {outcome === 'failed' ? (
          <Alert variant="light" color="red" title={`${title} ${plan.toVersion} did not start`}>
            <Stack gap="xs">
              <Text size="sm">{plugin?.failure ?? 'The server gave no reason.'}</Text>
              {plan.fromVersion && plan.activationClass === 'INSTANT' ? (
                <Text size="sm">{plan.fromVersion} is still running; nothing changed for its users.</Text>
              ) : null}
              <Text size="sm">Upload a fixed version, or open the plugin's details to retry or remove it.</Text>
            </Stack>
          </Alert>
        ) : null}
        {outcome === 'restart' ? (
          restart?.supervised || serverDown ? (
            <Alert variant="light" title="Studio is restarting">
              <Text size="sm">
                <Loader size="xs" mr={6} />
                {title} starts with it. This page reconnects by itself; everyone is disconnected until Studio is
                back, usually under a minute.
              </Text>
            </Alert>
          ) : (
            <Alert variant="light" title={`${title} starts when Studio restarts`}>
              <Stack gap="xs">
                <Text size="sm">Studio cannot restart itself where it runs. Restart it with:</Text>
                <Code block>{restart?.command ?? 'docker compose restart studio'}</Code>
              </Stack>
            </Alert>
          )
        ) : null}
      </div>
    </Stack>
  );
}

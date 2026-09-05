import { useState } from 'react';
import {
  Anchor,
  Button,
  Checkbox,
  Group,
  MultiSelect,
  NumberInput,
  Select,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';

import type { AlertRuleRequest, AlertRuleView, NotificationChannelView } from '../api/client.ts';
import {
  COMPARATORS,
  DERIVED_METRICS,
  GAUGE_METRICS,
  METRIC_NOTES,
  RATE_METRICS,
  SLOW_CONSUMER_TEMPLATE,
  STATE_CONDITIONS,
  metricKind,
  metricLabel,
} from './severity.ts';

const METRIC_OPTIONS = [...GAUGE_METRICS, ...RATE_METRICS, ...DERIVED_METRICS].map((m) => ({
  value: m,
  label: `${metricLabel(m)} (${metricKind(m)})`,
}));
const STATE_OPTIONS = STATE_CONDITIONS.map((c) => ({ value: c, label: c.replace(/_/g, ' ').toLowerCase() }));
const SEVERITY_OPTIONS = ['INFO', 'WARNING', 'CRITICAL'];

/**
 * Create/edit an alert rule — a threshold rule (metric + comparator + threshold)
 * or a state-condition rule (a closed set of HA transitions), never both
 * (alerting spec). Submitting clears the fields the other kind does not use.
 */
export function RuleForm({
  channels,
  initial,
  onSubmit,
  submitting,
  onCancel,
}: {
  channels: NotificationChannelView[];
  initial?: AlertRuleView;
  onSubmit: (body: AlertRuleRequest) => void;
  submitting: boolean;
  onCancel?: () => void;
}) {
  const [kind, setKind] = useState<'METRIC_THRESHOLD' | 'STATE'>(
    (initial?.kind as 'METRIC_THRESHOLD' | 'STATE') ?? 'METRIC_THRESHOLD',
  );
  const [name, setName] = useState(initial?.name ?? '');
  const [metric, setMetric] = useState<string | null>(initial?.metric ?? null);
  const [comparator, setComparator] = useState<string | null>(initial?.comparator ?? 'GT');
  const [threshold, setThreshold] = useState<number | ''>(initial?.threshold ?? '');
  const [stateCondition, setStateCondition] = useState<string | null>(initial?.stateCondition ?? null);
  const [forSeconds, setForSeconds] = useState<number | ''>(initial?.forSeconds ?? 60);
  const [severity, setSeverity] = useState<string | null>(initial?.severity ?? 'WARNING');
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [channelIds, setChannelIds] = useState<string[]>(initial?.channelIds ?? []);

  /**
   * A prefilled starting point, not a seeded rule (ADR-0044): no slow-consumer rule
   * is created on cluster registration, because a meaningful threshold is
   * workload-specific and any shipped value would be wrong for most deployments.
   */
  const applySlowConsumerTemplate = () => {
    setName(SLOW_CONSUMER_TEMPLATE.name);
    setMetric(SLOW_CONSUMER_TEMPLATE.metric);
    setComparator(SLOW_CONSUMER_TEMPLATE.comparator);
    setThreshold(SLOW_CONSUMER_TEMPLATE.threshold);
    setForSeconds(SLOW_CONSUMER_TEMPLATE.forSeconds);
    setSeverity(SLOW_CONSUMER_TEMPLATE.severity);
  };

  const valid =
    name.trim() &&
    severity &&
    (kind === 'METRIC_THRESHOLD'
      ? metric && comparator && threshold !== ''
      : Boolean(stateCondition));

  const submit = () => {
    if (!valid) return;
    onSubmit({
      name: name.trim(),
      kind,
      metric: kind === 'METRIC_THRESHOLD' ? (metric ?? undefined) : undefined,
      comparator: kind === 'METRIC_THRESHOLD' ? (comparator ?? undefined) : undefined,
      threshold: kind === 'METRIC_THRESHOLD' && threshold !== '' ? threshold : undefined,
      stateCondition: kind === 'STATE' ? (stateCondition ?? undefined) : undefined,
      forSeconds: forSeconds === '' ? 0 : forSeconds,
      severity: severity!,
      enabled,
      channelIds,
    });
  };

  return (
    <Stack gap="xs">
      <Group align="flex-end" gap="xs" wrap="wrap">
        <Select
          label="Kind"
          data={[
            { value: 'METRIC_THRESHOLD', label: 'Metric threshold' },
            { value: 'STATE', label: 'Cluster state' },
          ]}
          value={kind}
          onChange={(v) => setKind((v as 'METRIC_THRESHOLD' | 'STATE') ?? 'METRIC_THRESHOLD')}
          w={170}
          allowDeselect={false}
        />
        <TextInput
          label="Name"
          placeholder="Deep order queue"
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
          w={200}
        />

        {kind === 'METRIC_THRESHOLD' && !initial ? (
          <Anchor
            component="button"
            type="button"
            size="xs"
            onClick={applySlowConsumerTemplate}
            style={{ alignSelf: 'flex-end', paddingBottom: 8 }}
          >
            Start from the slow-consumer template
          </Anchor>
        ) : null}

        {kind === 'METRIC_THRESHOLD' ? (
          <>
            <Select
              label="Metric"
              placeholder="Choose a metric"
              data={METRIC_OPTIONS}
              value={metric}
              onChange={setMetric}
              w={220}
            />
            <Select
              label="Comparator"
              data={COMPARATORS.map((c) => ({ value: c, label: c }))}
              value={comparator}
              onChange={setComparator}
              w={110}
              allowDeselect={false}
            />
            <NumberInput
              label="Threshold"
              value={threshold}
              onChange={(v) => setThreshold(typeof v === 'number' ? v : '')}
              w={120}
            />
          </>
        ) : (
          <Select
            label="State condition"
            placeholder="Choose a condition"
            data={STATE_OPTIONS}
            value={stateCondition}
            onChange={setStateCondition}
            w={220}
          />
        )}

        <NumberInput
          label="For (seconds)"
          description="0 fires immediately"
          value={forSeconds}
          onChange={(v) => setForSeconds(typeof v === 'number' ? v : '')}
          min={0}
          w={140}
        />
        <Select
          label="Severity"
          data={SEVERITY_OPTIONS}
          value={severity}
          onChange={setSeverity}
          w={130}
          allowDeselect={false}
        />
      </Group>

      {metric && METRIC_NOTES[metric] ? (
        <Text size="xs" c="dimmed" maw={720}>
          {METRIC_NOTES[metric]}
        </Text>
      ) : null}

      <Group align="flex-end" gap="xs" wrap="wrap">
        <MultiSelect
          label="Notify channels"
          placeholder={channels.length ? 'None selected' : 'No channels configured yet'}
          data={channels.map((c) => ({ value: c.id, label: c.name }))}
          value={channelIds}
          onChange={setChannelIds}
          w={320}
          disabled={channels.length === 0}
        />
        <Checkbox
          label="Enabled"
          checked={enabled}
          onChange={(e) => setEnabled(e.currentTarget.checked)}
          mb={8}
        />
        <Group gap="xs">
          <Button onClick={submit} loading={submitting} disabled={!valid}>
            {initial ? 'Save' : 'Add rule'}
          </Button>
          {onCancel ? (
            <Button variant="default" onClick={onCancel}>
              Cancel
            </Button>
          ) : null}
        </Group>
      </Group>
    </Stack>
  );
}

import { useRef, useState } from 'react';
import { Button, Checkbox, MultiSelect, NumberInput, Select, Text, TextInput } from '@mantine/core';

import type { AlertRuleRequest, AlertRuleView, NotificationChannelView, PluginMetricView } from './api.ts';
import {
  COMPARATORS,
  DERIVED_METRICS,
  GAUGE_METRICS,
  METRIC_NOTES,
  RATE_METRICS,
  CONFIG_DRIFT_TEMPLATE,
  INSTALLATION_CONDITIONS,
  SETUP_RISK_TEMPLATE,
  SLOW_CONSUMER_TEMPLATE,
  STATE_CONDITIONS,
  metricKind,
  metricLabel,
} from './severity.ts';
import classes from './Alerting.module.css';

const STUDIO_METRIC_OPTIONS = [...GAUGE_METRICS, ...RATE_METRICS, ...DERIVED_METRICS].map((m) => ({
  value: m,
  label: `${metricLabel(m)} (${metricKind(m)})`,
}));

const UNIT_WORDS: Record<string, string> = { count: 'count', per_second: 'per second', ms: 'ms', ratio: 'ratio 0–1' };

/**
 * Studio's own metrics, then the metrics running plugins publish (ADR-0113). A rule being edited
 * whose plugin is not running keeps its metric on the list, marked, so the form never shows it
 * blank.
 */
function metricOptions(pluginMetrics: PluginMetricView[], current: string | null) {
  const plugin = pluginMetrics.map((m) => ({
    value: m.metric,
    label: `${m.metric} (${UNIT_WORDS[m.unit] ?? m.unit}, per ${m.subject})`,
  }));
  if (current?.includes(':') && !pluginMetrics.some((m) => m.metric === current)) {
    plugin.push({ value: current, label: `${current} (plugin not running)` });
  }
  return plugin.length
    ? [
        { group: 'Studio', items: STUDIO_METRIC_OPTIONS },
        { group: 'Plugins', items: plugin },
      ]
    : STUDIO_METRIC_OPTIONS;
}
const STATE_OPTIONS = STATE_CONDITIONS.map((c) => ({ value: c, label: c.replaceAll('_', ' ').toLowerCase() }));
const INSTALLATION_OPTIONS = INSTALLATION_CONDITIONS.map((c) => ({
  value: c,
  label: c.replaceAll('_', ' ').toLowerCase(),
}));
const SEVERITY_OPTIONS = ['INFO', 'WARNING', 'CRITICAL'];

function TemplateLink({ label, onClick }: Readonly<{ label: string; onClick: () => void }>) {
  return (
    <Button variant="subtle" size="compact-sm" onClick={onClick}>
      {`Start from the ${label} template`}
    </Button>
  );
}

/** The prefilled starting points that fit the chosen kind. */
function TemplateLinks({
  kind,
  onSlowConsumer,
  onConfigDrift,
  onSetupRisk,
}: Readonly<{
  kind: 'METRIC_THRESHOLD' | 'STATE';
  onSlowConsumer: () => void;
  onConfigDrift: () => void;
  onSetupRisk: () => void;
}>) {
  if (kind === 'METRIC_THRESHOLD') return <TemplateLink label="slow-consumer" onClick={onSlowConsumer} />;
  return (
    <>
      <TemplateLink label="configuration-drift" onClick={onConfigDrift} />
      <TemplateLink label="setup-risk" onClick={onSetupRisk} />
    </>
  );
}

type Field = 'name' | 'metric' | 'comparator' | 'threshold' | 'stateCondition';

type RuleDraft = {
  name: string;
  kind: 'METRIC_THRESHOLD' | 'STATE';
  metric: string | null;
  comparator: string | null;
  threshold: number | '';
  stateCondition: string | null;
  forSeconds: number | '';
  severity: string;
  enabled: boolean;
  channelIds: string[];
};

/** The request for a validated draft: only the fields of the chosen kind are sent. */
function ruleRequest(d: RuleDraft): AlertRuleRequest {
  const metricRule = d.kind === 'METRIC_THRESHOLD';
  return {
    name: d.name.trim(),
    kind: d.kind,
    metric: metricRule ? (d.metric ?? undefined) : undefined,
    comparator: metricRule ? (d.comparator ?? undefined) : undefined,
    threshold: metricRule && d.threshold !== '' ? d.threshold : undefined,
    stateCondition: metricRule ? undefined : (d.stateCondition ?? undefined),
    forSeconds: d.forSeconds === '' ? 0 : d.forSeconds,
    severity: d.severity,
    enabled: d.enabled,
    channelIds: d.channelIds,
  };
}

/**
 * Create/edit an alert rule — a threshold rule (metric + comparator + threshold)
 * or a state-condition rule (a closed set of HA transitions), never both
 * (alerting spec). Submitting clears the fields the other kind does not use.
 */
export function RuleForm({
  channels,
  pluginMetrics = [],
  initial,
  onSubmit,
  submitting,
  onCancel,
  disabled = false,
}: Readonly<{
  channels: NotificationChannelView[];
  /** The metrics running plugins publish. */
  pluginMetrics?: PluginMetricView[];
  initial?: AlertRuleView;
  onSubmit: (body: AlertRuleRequest) => void;
  submitting: boolean;
  onCancel?: () => void;
  /** The caller may not change rules; the form stays visible and the view says why beside it. */
  disabled?: boolean;
}>) {
  // A rule with no cluster is about Studio itself: always a state rule, on a storage condition (ADR-0135).
  const installation = initial !== undefined && !initial.clusterId;
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

  const applyConfigDriftTemplate = () => {
    setName(CONFIG_DRIFT_TEMPLATE.name);
    setStateCondition(CONFIG_DRIFT_TEMPLATE.stateCondition);
    setForSeconds(CONFIG_DRIFT_TEMPLATE.forSeconds);
    setSeverity(CONFIG_DRIFT_TEMPLATE.severity);
  };

  const applySetupRiskTemplate = () => {
    setName(SETUP_RISK_TEMPLATE.name);
    setStateCondition(SETUP_RISK_TEMPLATE.stateCondition);
    setForSeconds(SETUP_RISK_TEMPLATE.forSeconds);
    setSeverity(SETUP_RISK_TEMPLATE.severity);
  };

  const pluginMetric = pluginMetrics.find((m) => m.metric === metric);

  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const refs = useRef<Partial<Record<Field, HTMLElement | null>>>({});
  const register = (f: Field) => (el: HTMLElement | null) => {
    refs.current[f] = el;
  };

  /** What is wrong with a field now, or nothing. Only the fields of the chosen kind are checked. */
  const problem = (f: Field): string | undefined => {
    switch (f) {
      case 'name':
        return name.trim() ? undefined : 'Enter a name, so the rule and its alerts can be told apart.';
      case 'metric':
        return kind === 'METRIC_THRESHOLD' && !metric ? 'Choose the metric the rule watches.' : undefined;
      case 'comparator':
        return kind === 'METRIC_THRESHOLD' && !comparator ? 'Choose how the metric is compared.' : undefined;
      case 'threshold':
        return kind === 'METRIC_THRESHOLD' && threshold === ''
          ? 'Enter the value that makes the rule fire.'
          : undefined;
      case 'stateCondition':
        return kind === 'STATE' && !stateCondition ? 'Choose the cluster state the rule watches.' : undefined;
    }
  };
  const fieldsOf = (): Field[] =>
    kind === 'METRIC_THRESHOLD' ? ['name', 'metric', 'comparator', 'threshold'] : ['name', 'stateCondition'];
  const blur = (f: Field) => () => setErrors((e) => ({ ...e, [f]: problem(f) }));
  const clear = (f: Field) => setErrors((e) => (e[f] ? { ...e, [f]: undefined } : e));

  const submit = () => {
    const next: Partial<Record<Field, string>> = {};
    for (const f of fieldsOf()) next[f] = problem(f);
    setErrors(next);
    const first = fieldsOf().find((f) => next[f]);
    if (first) {
      refs.current[first]?.focus();
      return;
    }
    if (!severity) return;
    onSubmit(
      ruleRequest({
        name,
        kind,
        metric,
        comparator,
        threshold,
        stateCondition,
        forSeconds,
        severity,
        enabled,
        channelIds,
      }),
    );
  };

  return (
    <form
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <fieldset className={classes.fieldset} disabled={disabled}>
        <div className={classes.form}>
          <Select
            label="Kind"
            data={[
              { value: 'METRIC_THRESHOLD', label: 'Metric threshold' },
              { value: 'STATE', label: 'Cluster state' },
            ]}
            value={kind}
            onChange={(v) => {
              setKind((v as 'METRIC_THRESHOLD' | 'STATE') ?? 'METRIC_THRESHOLD');
              setErrors({});
            }}
            allowDeselect={false}
            disabled={installation}
          />
          <TextInput
            ref={register('name')}
            label="Name"
            placeholder="Deep order queue"
            value={name}
            onChange={(e) => {
              setName(e.currentTarget.value);
              clear('name');
            }}
            onBlur={blur('name')}
            error={errors.name}
          />

          {kind === 'METRIC_THRESHOLD' ? (
            <>
              <Select
                ref={register('metric')}
                label="Metric"
                placeholder="Choose a metric"
                data={metricOptions(pluginMetrics, metric)}
                value={metric}
                onChange={(v) => {
                  setMetric(v);
                  clear('metric');
                }}
                onBlur={blur('metric')}
                error={errors.metric}
              />
              <Select
                ref={register('comparator')}
                label="Comparator"
                data={COMPARATORS.map((c) => ({ value: c, label: c }))}
                value={comparator}
                onChange={(v) => {
                  setComparator(v);
                  clear('comparator');
                }}
                onBlur={blur('comparator')}
                error={errors.comparator}
                allowDeselect={false}
              />
              <NumberInput
                ref={register('threshold')}
                label="Threshold"
                value={threshold}
                onChange={(v) => {
                  setThreshold(typeof v === 'number' ? v : '');
                  clear('threshold');
                }}
                onBlur={blur('threshold')}
                error={errors.threshold}
              />
            </>
          ) : (
            <Select
              ref={register('stateCondition')}
              label="State condition"
              placeholder="Choose a condition"
              data={installation ? INSTALLATION_OPTIONS : STATE_OPTIONS}
              value={stateCondition}
              onChange={(v) => {
                setStateCondition(v);
                clear('stateCondition');
              }}
              onBlur={blur('stateCondition')}
              error={errors.stateCondition}
            />
          )}

          <NumberInput
            label="For (seconds)"
            description="0 fires immediately"
            value={forSeconds}
            onChange={(v) => setForSeconds(typeof v === 'number' ? v : '')}
            min={0}
          />
          <Select
            label="Severity"
            data={SEVERITY_OPTIONS}
            value={severity}
            onChange={setSeverity}
            allowDeselect={false}
          />
          <MultiSelect
            label="Notify channels"
            placeholder={channels.length ? 'None selected' : 'No channels configured yet'}
            data={channels.map((c) => ({ value: c.id, label: c.name }))}
            value={channelIds}
            onChange={setChannelIds}
            disabled={channels.length === 0}
          />

          {metric && METRIC_NOTES[metric] ? (
            <Text size="xs" c="dimmed" className={`${classes.wide} ${classes.help}`}>
              {METRIC_NOTES[metric]}
            </Text>
          ) : null}
          {kind === 'METRIC_THRESHOLD' && pluginMetric ? (
            <Text size="xs" c="dimmed" className={`${classes.wide} ${classes.help}`}>
              {pluginMetric.description} Published by the {pluginMetric.plugin} plugin and sampled on every queue
              scrape; the rule fires per {pluginMetric.subject}.
            </Text>
          ) : null}

          <div className={`${classes.wide} ${classes.actions}`}>
            <Checkbox label="Enabled" checked={enabled} onChange={(e) => setEnabled(e.currentTarget.checked)} />
            <Button type="submit" loading={submitting}>
              {initial ? 'Save' : 'Add rule'}
            </Button>
            {onCancel ? (
              <Button variant="default" onClick={onCancel}>
                Cancel
              </Button>
            ) : null}
            {initial ? null : (
              <TemplateLinks
                kind={kind}
                onSlowConsumer={applySlowConsumerTemplate}
                onConfigDrift={applyConfigDriftTemplate}
                onSetupRisk={applySetupRiskTemplate}
              />
            )}
          </div>
        </div>
      </fieldset>
    </form>
  );
}

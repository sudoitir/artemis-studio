import { useRef, useState } from 'react';
import {
  Alert,
  Anchor,
  Button,
  Group,
  Modal,
  PasswordInput,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import {
  useCreateNotificationChannel,
  useTestChannelConfig,
  useUpdateNotificationChannel,
  type ChannelTestResultView,
  type NotificationChannelView,
} from './api.ts';
import {
  CHANNEL_KINDS,
  EMPTY_FIELDS,
  KIND_ORDER,
  PAGERDUTY_ENDPOINTS,
  configFromFields,
  fieldsFromConfig,
  generateSigningSecret,
  kindLabel,
  serverField,
  validateField,
  type ChannelFields,
  type ChannelKind,
} from './channelKinds.ts';

type Field = keyof ChannelFields | 'name' | 'secret';

/** The fields each kind shows, in order; validated on submit in this order too. */
const FIELDS: Record<ChannelKind, Field[]> = {
  SLACK: ['name', 'secret'],
  TEAMS: ['name', 'secret'],
  PAGERDUTY: ['name', 'secret', 'url'],
  EMAIL: ['name', 'host', 'port', 'from', 'to', 'secret'],
  WEBHOOK: ['name', 'url', 'secret'],
};

/**
 * Create or edit a notification channel (ADR-0105). The kind is chosen once: it is a
 * fact about an existing channel, shown as text, never a disabled select. The secret is
 * write-only — blank on edit, and a blank secret keeps the stored one — and a test can
 * be sent before saving, so a wrong URL is found here rather than at 3 a.m.
 */
export function ChannelEditor({
  opened,
  channel,
  onClose,
  onSaved,
}: {
  opened: boolean;
  channel: NotificationChannelView | null;
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={channel ? `Edit ${channel.name}` : 'Add a notification channel'}
      size="lg"
    >
      {/* Remounted per channel, so the form never shows a previous channel's values. */}
      {opened ? <ChannelForm key={channel?.id ?? 'new'} channel={channel} onClose={onClose} onSaved={onSaved} /> : null}
    </Modal>
  );
}

function ChannelForm({
  channel,
  onClose,
  onSaved,
}: {
  channel: NotificationChannelView | null;
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  const editing = channel !== null;
  const [kind, setKind] = useState<ChannelKind>((channel?.kind as ChannelKind) ?? 'SLACK');
  const [name, setName] = useState(channel?.name ?? '');
  const [enabled, setEnabled] = useState(channel?.enabled ?? true);
  const [fields, setFields] = useState<ChannelFields>(
    channel ? fieldsFromConfig(channel.kind, channel.config) : EMPTY_FIELDS,
  );
  const [secret, setSecret] = useState('');
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [testResult, setTestResult] = useState<ChannelTestResultView | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const refs = useRef<Partial<Record<Field, HTMLElement | null>>>({});

  const create = useCreateNotificationChannel();
  const update = useUpdateNotificationChannel();
  const test = useTestChannelConfig();
  const info = CHANNEL_KINDS[kind];
  const hasSecret = channel?.hasSecret ?? false;

  const valueOf = (f: Field): string => (f === 'name' ? name : f === 'secret' ? secret : String(fields[f]));
  const check = (f: Field, value = valueOf(f)) =>
    validateField(kind, f, value, { editing, hasSecret, fields });
  const blur = (f: Field) => () => setErrors((e) => ({ ...e, [f]: check(f) ?? undefined }));
  const set = (f: keyof ChannelFields) => (value: string) => {
    setFields((prev) => ({ ...prev, [f]: value }));
    setTestResult(null);
    if (errors[f]) setErrors((e) => ({ ...e, [f]: undefined }));
  };

  /** Validates every shown field; on failure focuses the first invalid one. */
  const validateAll = (only?: Field[]): boolean => {
    const next: Partial<Record<Field, string>> = {};
    for (const f of only ?? FIELDS[kind]) {
      const message = check(f);
      if (message) next[f] = message;
    }
    setErrors(next);
    const first = (only ?? FIELDS[kind]).find((f) => next[f]);
    if (first) {
      refs.current[first]?.focus();
      return false;
    }
    return true;
  };

  const rejected = (e: ApiError) => {
    const field = serverField(e.message) as Field | null;
    if (field && FIELDS[kind].includes(field)) {
      setErrors((prev) => ({ ...prev, [field]: e.message.replace(/^[A-Za-z]+:\s/, '') }));
      refs.current[field]?.focus();
    } else {
      setFormError(e.message);
    }
  };

  const runTest = () => {
    setFormError(null);
    setTestResult(null);
    if (!validateAll(FIELDS[kind].filter((f) => f !== 'name'))) return;
    test.mutate(
      {
        channelId: channel?.id ?? null,
        kind,
        config: configFromFields(kind, fields),
        secret: secret.trim() || undefined,
      },
      { onSuccess: setTestResult, onError: rejected },
    );
  };

  const save = () => {
    setFormError(null);
    if (!validateAll()) return;
    const body = {
      name: name.trim(),
      kind,
      config: configFromFields(kind, fields),
      secret: secret.trim() || undefined,
      enabled,
    };
    const done = () => {
      onSaved(editing ? `Saved "${body.name}".` : `Added "${body.name}".`);
      onClose();
    };
    if (channel) {
      update.mutate({ channelId: channel.id, body }, { onSuccess: done, onError: rejected });
    } else {
      create.mutate(body, { onSuccess: done, onError: rejected });
    }
  };

  const saving = create.isPending || update.isPending;
  const register = (f: Field) => (el: HTMLElement | null) => {
    refs.current[f] = el;
  };

  return (
    <Stack gap="sm">
      {editing ? (
        <Text size="sm">
          <Text span fw={600}>
            Kind:
          </Text>{' '}
          {kindLabel(kind)} — a channel’s kind cannot change; add a new channel for another kind.
        </Text>
      ) : (
        <Select
          label="Kind"
          data={KIND_ORDER.map((k) => ({ value: k, label: CHANNEL_KINDS[k].label }))}
          value={kind}
          onChange={(v) => {
            if (!v) return;
            setKind(v as ChannelKind);
            setErrors({});
            setTestResult(null);
          }}
          allowDeselect={false}
        />
      )}
      <Text size="sm" c="dimmed">
        {info.description}
      </Text>

      <TextInput
        ref={register('name')}
        label="Name"
        description="How rules and the channel list refer to it."
        value={name}
        onChange={(e) => setName(e.currentTarget.value)}
        onBlur={blur('name')}
        error={errors.name}
        required
      />

      {kind === 'WEBHOOK' ? (
        <TextInput
          ref={register('url')}
          label="Receiver URL"
          value={fields.url}
          onChange={(e) => set('url')(e.currentTarget.value)}
          onBlur={blur('url')}
          error={errors.url}
          placeholder="https://alerts.example.com/hooks/artemis"
          required
        />
      ) : null}

      {kind === 'EMAIL' ? (
        <>
          <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs">
            <TextInput
              ref={register('host')}
              label="SMTP server"
              value={fields.host}
              onChange={(e) => set('host')(e.currentTarget.value)}
              onBlur={blur('host')}
              error={errors.host}
              placeholder="smtp.example.com"
              required
            />
            <TextInput
              ref={register('port')}
              label="Port"
              inputMode="numeric"
              value={fields.port}
              onChange={(e) => set('port')(e.currentTarget.value)}
              onBlur={blur('port')}
              error={errors.port}
              required
            />
            <Stack gap={4}>
              <Text size="sm" fw={500} id="smtp-security-label">
                Transport security
              </Text>
              <SegmentedControl
                aria-labelledby="smtp-security-label"
                size="xs"
                value={fields.security}
                onChange={(v) => {
                  set('security')(v);
                  if (v === 'TLS' && fields.port === '587') set('port')('465');
                  if (v === 'STARTTLS' && fields.port === '465') set('port')('587');
                }}
                data={[
                  { value: 'STARTTLS', label: 'STARTTLS' },
                  { value: 'TLS', label: 'TLS' },
                  { value: 'NONE', label: 'None' },
                ]}
              />
            </Stack>
          </SimpleGrid>
          {fields.security === 'NONE' ? (
            <Text size="xs" c="var(--as-warning)">
              Without TLS the password and the alert cross the network in clear. STARTTLS, when chosen, is required —
              a server that does not offer it fails the delivery rather than receiving it unencrypted.
            </Text>
          ) : null}
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
            <TextInput
              ref={register('from')}
              label="From"
              value={fields.from}
              onChange={(e) => set('from')(e.currentTarget.value)}
              onBlur={blur('from')}
              error={errors.from}
              placeholder="artemis-studio@example.com"
              required
            />
            <TextInput
              label="Username"
              description="Blank when the server needs no authentication."
              value={fields.username}
              onChange={(e) => set('username')(e.currentTarget.value)}
              autoComplete="off"
            />
          </SimpleGrid>
          <Textarea
            ref={register('to')}
            label="Recipients"
            description="Separated by commas or new lines."
            value={fields.to}
            onChange={(e) => set('to')(e.currentTarget.value)}
            onBlur={blur('to')}
            error={errors.to}
            autosize
            minRows={2}
            required
          />
          <TextInput
            label="Subject prefix"
            description="Put before every subject, so a mail rule can file alerts."
            value={fields.subjectPrefix}
            onChange={(e) => set('subjectPrefix')(e.currentTarget.value)}
          />
        </>
      ) : null}

      <PasswordInput
        ref={register('secret')}
        label={info.secretLabel}
        description={
          editing && hasSecret
            ? `${info.secretDescription} A secret is stored; leave blank to keep it.`
            : info.secretDescription
        }
        placeholder={editing && hasSecret ? '•••••••• (stored — unchanged)' : info.secretPlaceholder}
        value={secret}
        onChange={(e) => {
          setSecret(e.currentTarget.value);
          setTestResult(null);
          if (errors.secret) setErrors((x) => ({ ...x, secret: undefined }));
        }}
        onBlur={blur('secret')}
        error={errors.secret}
        autoComplete="new-password"
        required={!info.secretOptional && !(editing && hasSecret)}
      />
      {kind === 'WEBHOOK' ? (
        <Anchor component="button" type="button" size="xs" onClick={() => setSecret(generateSigningSecret())}>
          Generate a random signing secret
        </Anchor>
      ) : null}

      {kind === 'PAGERDUTY' ? (
        <>
          <Select
            label="Endpoint"
            data={PAGERDUTY_ENDPOINTS.map((e) => ({ value: e.value, label: e.label }))}
            value={fields.pagerDutyEndpoint}
            onChange={(v) => v && set('pagerDutyEndpoint')(v)}
            allowDeselect={false}
          />
          {fields.pagerDutyEndpoint === 'custom' ? (
            <TextInput
              ref={register('url')}
              label="Events API v2 URL"
              value={fields.url}
              onChange={(e) => set('url')(e.currentTarget.value)}
              onBlur={blur('url')}
              error={errors.url}
              placeholder="https://oncall.example.com/v2/enqueue"
              required
            />
          ) : null}
        </>
      ) : null}

      <Switch
        label={enabled ? 'Enabled — bound rules deliver here' : 'Disabled — bound rules skip this channel'}
        checked={enabled}
        onChange={(e) => setEnabled(e.currentTarget.checked)}
      />

      <div role="status" aria-live="polite">
        {testResult ? <TestOutcome result={testResult} kind={kind} /> : null}
      </div>
      {formError ? (
        <Alert color="red" variant="light" title="Not saved" role="alert">
          {formError}
        </Alert>
      ) : null}

      <Group justify="space-between">
        <Button variant="default" onClick={runTest} loading={test.isPending} disabled={saving}>
          Send a test
        </Button>
        <Group gap="xs">
          <Button variant="subtle" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={save} loading={saving} disabled={test.isPending}>
            {editing ? 'Save' : 'Add channel'}
          </Button>
        </Group>
      </Group>
    </Stack>
  );
}

/** A test outcome with its cause and what to do next — never just "failed". */
export function TestOutcome({ result, kind }: { result: ChannelTestResultView; kind: string }) {
  if (result.delivered) {
    return (
      <Alert color="gray" variant="light" title={`Test delivered in ${result.durationMs} ms`}>
        {kind === 'PAGERDUTY'
          ? 'PagerDuty accepted a test incident and its resolution; it will appear already resolved.'
          : 'Check the destination for a message titled "Test notification from Artemis Studio".'}
      </Alert>
    );
  }
  return (
    <Alert color="red" variant="light" title="Test not delivered">
      <Text size="sm">{result.error ?? 'The receiver gave no reason.'}</Text>
      <Text size="sm" mt={4}>
        {result.permanent
          ? 'Retrying will not help: fix the URL, key or credentials and test again.'
          : 'This may be temporary — the receiver was unreachable or overloaded. Real deliveries are retried with backoff.'}
      </Text>
    </Alert>
  );
}

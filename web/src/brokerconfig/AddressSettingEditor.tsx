import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button, Chip, Collapse, Group, NumberInput, Select, Stack, Text, TextInput } from '@mantine/core';

import { useDlq } from '../api/client.ts';
import type {
  ConfigAddressSettingKeyView,
  ConfigAddressSettingView,
  ConfigCatalogueView,
  ConfigDeclarationView,
} from '../api/client.ts';
import { keyTaken, removeItem, upsertAddressSetting } from './document.ts';
import { EditorDrawer, MATCH_HINT } from './EditorDrawer.tsx';
import { keyHelp } from './keyHelp.ts';
import { KeyHint } from './KeyHint.tsx';
import { useSaveDocument } from './useSaveDocument.ts';

/**
 * The keys most declarations set, shown first. Everything else the catalogue
 * knows is behind the disclosure — it is the long tail, and the form should not
 * make an operator scroll past forty inputs to find the policy.
 */
const COMMON_KEYS = [
  'addressFullMessagePolicy',
  'maxSizeBytes',
  'pageSizeBytes',
  'deadLetterAddress',
  'expiryAddress',
  'maxDeliveryAttempts',
  'redeliveryDelay',
];

/**
 * A persistent note beside a value whose consequence an operator on call must
 * not discover after applying. Only the values that carry the consequence, so
 * the note means something when it appears.
 */
function hazardNote(key: string, value: string): string | undefined {
  if (key === 'addressFullMessagePolicy') {
    if (value === 'DROP') return 'DROP discards new messages silently once the limit is hit.';
    if (value === 'FAIL') return 'FAIL refuses new messages with an error once the limit is hit.';
    if (value === 'BLOCK') return 'BLOCK stalls producers once the limit is hit.';
  }
  if (key === 'pageFullMessagePolicy' && value === 'FAIL') {
    return 'FAIL refuses new messages once the page limit is hit.';
  }
  if ((key === 'autoDeleteQueues' || key === 'autoDeleteAddresses') && value === 'true') {
    return 'The broker will remove idle ones on its own.';
  }
  return undefined;
}

type Values = Record<string, string>;

function toValues(item: ConfigAddressSettingView | null): Values {
  const out: Values = {};
  if (!item) return out;
  for (const [k, v] of Object.entries(item.values)) {
    if (v !== null && v !== undefined) out[k] = String(v);
  }
  return out;
}

function toWire(values: Values, catalogue: ConfigCatalogueView): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const key of catalogue.addressSettingKeys) {
    const raw = values[key.jsonName];
    if (raw === undefined || raw === '') continue;
    switch (key.type) {
      case 'BOOLEAN':
        out[key.jsonName] = raw === 'true';
        break;
      case 'INT':
      case 'LONG':
      case 'DOUBLE':
        out[key.jsonName] = Number(raw);
        break;
      default:
        out[key.jsonName] = raw;
    }
  }
  return out;
}

interface Errors {
  match?: string;
}

/**
 * Edit one address-setting match. The catalogue (ADR-0067 D10) decides which
 * keys exist, their types and their allowed values; the form does not know a
 * key by name except to decide which ones come first.
 *
 * <p>Replace semantics are stated on the match itself: the broker replaces the
 * whole entry when this is applied, so a key left undeclared here is not "kept",
 * it falls back to the parent match. The plan shows that as an unintended
 * change before anything is written; this is where the operator learns why.
 */
export function AddressSettingEditor({
  declaration,
  catalogue,
  item,
  opened,
  onClose,
}: {
  declaration: ConfigDeclarationView;
  catalogue: ConfigCatalogueView;
  /** Null creates a new match. */
  item: ConfigAddressSettingView | null;
  opened: boolean;
  onClose: () => void;
}) {
  const [match, setMatch] = useState(item?.match ?? '');
  const [values, setValues] = useState<Values>(toValues(item));
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const [filter, setFilter] = useState('');
  const matchRef = useRef<HTMLInputElement>(null);

  // Templates are built from this cluster's own dead-letter and expiry addresses,
  // never from invented names: a prefilled DLQ that does not exist declares a
  // policy that silently routes nowhere.
  const dlq = useDlq(declaration.clusterId);
  const deadLetter = dlq.data?.addresses.find((a) => a.kind === 'dead-letter')?.address;
  const expiry = dlq.data?.addresses.find((a) => a.kind === 'expiry')?.address;
  const templates = [
    deadLetter
      ? {
          id: 'retry-then-dlq',
          label: 'Retry, then dead-letter',
          description: `Three delayed redeliveries, then ${deadLetter}.`,
          values: {
            maxDeliveryAttempts: '3',
            redeliveryDelay: '5000',
            redeliveryMultiplier: '2',
            maxRedeliveryDelay: '60000',
            deadLetterAddress: deadLetter,
          } as Values,
        }
      : null,
    deadLetter
      ? {
          id: 'dlq-policy',
          label: 'Dead-letter only',
          description: `Undeliverable messages go to ${deadLetter}, first failure.`,
          values: { maxDeliveryAttempts: '1', deadLetterAddress: deadLetter } as Values,
        }
      : null,
    expiry
      ? {
          id: 'expiry-policy',
          label: 'Expiry',
          description: `Expired messages go to ${expiry} rather than being dropped.`,
          values: { expiryAddress: expiry } as Values,
        }
      : null,
  ].filter((t): t is { id: string; label: string; description: string; values: Values } => t !== null);

  useEffect(() => {
    if (!opened) return;
    setMatch(item?.match ?? '');
    setValues(toValues(item));
    setTouched({});
    setSubmitted(false);
    setAdvanced(false);
    setFilter('');
  }, [opened, item]);

  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);

  const validate = (): Errors => {
    const errors: Errors = {};
    const m = match.trim();
    if (!m) errors.match = 'A match pattern is required — it names the addresses these settings apply to.';
    else if (keyTaken(declaration.document.addressSettings, (i) => i.match, m, item?.match)) {
      errors.match = `"${m}" is already declared. Edit that entry instead.`;
    }
    return errors;
  };
  const errors = validate();
  const errorFor = (field: keyof Errors) => (touched[field] || submitted ? errors[field] : undefined);

  const submit = () => {
    setSubmitted(true);
    if (Object.keys(errors).length > 0) {
      matchRef.current?.focus();
      return;
    }
    const next: ConfigAddressSettingView = { match: match.trim(), values: toWire(values, catalogue) };
    save(
      upsertAddressSetting(declaration.document, next, item?.match),
      `${item ? 'Edited' : 'Added'} address setting ${next.match}`,
    );
  };

  const remove = () =>
    save(removeItem(declaration.document, 'addressSettings', item!.match), `Removed address setting ${item!.match}`);

  const close = () => {
    reset();
    onClose();
  };

  const keys = catalogue.addressSettingKeys.filter((k) => k.applicable);
  const common = COMMON_KEYS.map((name) => keys.find((k) => k.jsonName === name)).filter(
    (k): k is ConfigAddressSettingKeyView => k !== undefined,
  );
  const rest = keys.filter((k) => !COMMON_KEYS.includes(k.jsonName));
  const declaredInRest = rest.filter((k) => values[k.jsonName] !== undefined && values[k.jsonName] !== '').length;
  // Fifty-odd keys behind one disclosure need a way in: match the element name,
  // the JSON name or a word of the explanation.
  const needle = filter.trim().toLowerCase();
  const shownRest = needle
    ? rest.filter((k) => {
        const help = keyHelp(k.jsonName);
        return (
          k.xmlName.includes(needle) ||
          k.jsonName.toLowerCase().includes(needle) ||
          (help?.summary.toLowerCase().includes(needle) ?? false)
        );
      })
    : rest;

  const field = (key: ConfigAddressSettingKeyView) => {
    const value = values[key.jsonName] ?? '';
    const set = (v: string) => setValues((prev) => ({ ...prev, [key.jsonName]: v }));
    const note = hazardNote(key.jsonName, value);
    const description = note ?? (key.hazardClass === 'HIGH' ? 'Changing this is a High hazard; the plan will ask for acknowledgement.' : undefined);
    const help = keyHelp(key.jsonName);
    const label = key.xmlName;
    // The explanation sits beside the input, not inside the label, so it is a
    // control of its own (keyboard-reachable) and does not join the field's name.
    const inputContainer = help
      ? (children: ReactNode) => (
          <Group gap={4} wrap="nowrap" align="center">
            <div style={{ flex: 1, minWidth: 0 }}>{children}</div>
            <KeyHint name={key.xmlName} help={help} />
          </Group>
        )
      : undefined;
    switch (key.type) {
      case 'BOOLEAN':
        return (
          <Select
            key={key.jsonName}
            label={label}
            description={description}
            inputContainer={inputContainer}
            data={[
              { value: '', label: 'not declared' },
              { value: 'true', label: 'true' },
              { value: 'false', label: 'false' },
            ]}
            value={value}
            onChange={(v) => set(v ?? '')}
            allowDeselect={false}
          />
        );
      case 'ENUM':
        return (
          <Select
            key={key.jsonName}
            label={label}
            description={description}
            inputContainer={inputContainer}
            data={[{ value: '', label: 'not declared' }, ...key.allowedValues.map((v) => ({ value: v, label: v }))]}
            value={value}
            onChange={(v) => set(v ?? '')}
            allowDeselect={false}
          />
        );
      case 'INT':
      case 'LONG':
      case 'DOUBLE':
        return (
          <NumberInput
            key={key.jsonName}
            label={label}
            description={description}
            inputContainer={inputContainer}
            value={value === '' ? '' : Number(value)}
            onChange={(v) => set(v === '' ? '' : String(v))}
            allowDecimal={key.type === 'DOUBLE'}
          />
        );
      default:
        return (
          <TextInput
            key={key.jsonName}
            label={label}
            description={description}
            inputContainer={inputContainer}
            value={value}
            onChange={(e) => set(e.currentTarget.value)}
          />
        );
    }
  };

  return (
    <EditorDrawer
      opened={opened}
      onClose={close}
      title={item ? `Address setting ${item.match}` : 'New address setting'}
      error={error}
      submitting={isPending}
      submitLabel={`Save as revision ${declaration.revision + 1}`}
      onSubmit={submit}
      hint={submitted && Object.keys(errors).length > 0 ? 'Fix the fields above to continue.' : undefined}
      secondary={
        item ? (
          <Button variant="subtle" color="red" size="xs" onClick={remove} loading={isPending}>
            Remove from declaration
          </Button>
        ) : null
      }
    >
      <TextInput
        ref={matchRef}
        label="Match pattern"
        description={MATCH_HINT}
        value={match}
        onChange={(e) => setMatch(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, match: true }))}
        error={errorFor('match')}
        required
      />

      {templates.length > 0 ? (
        <Stack gap={4}>
          <Text size="xs" fw={600}>
            Start from a template
          </Text>
          <Group gap="xs" wrap="wrap">
            {templates.map((t) => (
              <Chip
                key={t.id}
                size="xs"
                checked={false}
                onClick={() => setValues((prev) => ({ ...prev, ...t.values }))}
                title={t.description}
              >
                {t.label}
              </Chip>
            ))}
          </Group>
          <Text size="xs" c="dimmed">
            Fills the fields below from this cluster's own{' '}
            {[deadLetter, expiry].filter(Boolean).join(' and ')} — nothing is saved until you do.
          </Text>
        </Stack>
      ) : null}

      <Text size="xs" c="dimmed">
        Applying replaces the broker's whole entry for this match. A key not declared here is not kept — it falls
        back to the parent match. The plan lists every such change before anything is written. Keys the broker does
        not report back cannot be seen or kept; re-declare them here if your broker.xml sets them.
      </Text>

      <Stack gap="sm">{common.map(field)}</Stack>

      <div>
        <Button variant="subtle" size="xs" px={0} onClick={() => setAdvanced((a) => !a)} aria-expanded={advanced}>
          {advanced ? 'Hide the other keys' : `Other keys (${rest.length}${declaredInRest ? `, ${declaredInRest} declared` : ''})`}
        </Button>
        <Collapse expanded={advanced}>
          <Stack gap="sm" mt="sm">
            <TextInput
              label="Find a key"
              description="By element name, JSON name or a word from its explanation — “page”, “expiry”, “auto-delete”."
              value={filter}
              onChange={(e) => setFilter(e.currentTarget.value)}
              size="xs"
            />
            {shownRest.length === 0 ? (
              <Text size="xs" c="dimmed">
                No key matches “{filter}”. A key Studio does not know cannot be declared: the broker would accept it
                and silently ignore it.
              </Text>
            ) : (
              shownRest.map(field)
            )}
          </Stack>
        </Collapse>
      </div>
    </EditorDrawer>
  );
}

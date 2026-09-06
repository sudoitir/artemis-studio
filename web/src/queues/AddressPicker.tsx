import { useMemo, useRef, useState } from 'react';
import { Chip, Combobox, Group, Loader, Text, TextInput, useCombobox } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';

import styles from './AddressPicker.module.css';

import { useQueues, type QueueView } from '../api/client.ts';

const SUGGESTION_LIMIT = 300;
const ROUTING_TYPES = ['ANYCAST', 'MULTICAST'] as const;
type RoutingType = (typeof ROUTING_TYPES)[number];

export interface AddressPickerProps {
  clusterId: string;
  value: string;
  onChange: (value: string) => void;
  label?: string;
  description?: string;
  placeholder?: string;
  /** Shown under the field when what was typed matches no address on the broker. */
  unknownHint?: string;
  w?: number | string;
  disabled?: boolean;
}

/**
 * Pick an address by name, with the broker's own list to choose from.
 *
 * Free text is deliberately allowed: an operator may be naming an address that
 * does not exist yet, or one that only appears under load. So this suggests, it
 * never constrains — but it says so when what you typed matches nothing, because
 * a silent typo is the failure this component exists to prevent.
 *
 * Suggestions come from queues rather than addresses because a queue carries the
 * routing type and the depth, which is what tells a request queue from a reply
 * queue at a glance. Search runs on the server (`q`), so the list stays bounded
 * on a broker with thousands of queues.
 */
export function AddressPicker({
  clusterId,
  value,
  onChange,
  label,
  description,
  placeholder,
  unknownHint,
  w,
  disabled,
}: AddressPickerProps) {
  const combobox = useCombobox({ onDropdownClose: () => combobox.resetSelectedOption() });
  const [types, setTypes] = useState<RoutingType[]>([]);
  const [debounced] = useDebouncedValue(value, 200);
  // The dropdown holds its own focusable controls, so closing on every blur
  // would put the routing-type filter out of reach of the keyboard entirely.
  const root = useRef<HTMLDivElement>(null);

  const queues = useQueues(clusterId, { q: debounced || undefined, size: SUGGESTION_LIMIT });

  const options = useMemo(() => {
    const rows = queues.data?.data ?? [];
    const wanted = types.length === 0 ? null : new Set<string>(types);
    // One row per address: the same address on several nodes is one thing to
    // whoever is picking it, so keep the busiest and count the rest as coverage.
    const byAddress = new Map<string, QueueView>();
    for (const row of rows) {
      if (wanted && !wanted.has(row.routingType)) continue;
      const seen = byAddress.get(row.address);
      if (!seen || row.totalMessageCount > seen.totalMessageCount) byAddress.set(row.address, row);
    }
    return [...byAddress.values()].sort((a, b) => a.address.localeCompare(b.address));
  }, [queues.data, types]);

  const matchesKnownAddress = options.some((o) => o.address === value);
  const unknown =
    Boolean(unknownHint) && value.trim() !== '' && !queues.isFetching && !matchesKnownAddress;

  return (
    <div ref={root}>
      <Combobox
        store={combobox}
        withinPortal={false}
        onOptionSubmit={(v) => {
          onChange(v);
          combobox.closeDropdown();
        }}
      >
        <Combobox.Target>
          <TextInput
            label={label}
            description={description}
            placeholder={placeholder}
            value={value}
            disabled={disabled}
            w={w}
            autoComplete="off"
            spellCheck={false}
            onChange={(e) => {
              onChange(e.currentTarget.value);
              combobox.openDropdown();
              combobox.updateSelectedOptionIndex();
            }}
            onClick={() => combobox.openDropdown()}
            onFocus={() => combobox.openDropdown()}
            onBlur={(e) => {
              // Keep the dropdown open while focus moves to the filter inside it.
              if (root.current?.contains(e.relatedTarget)) return;
              combobox.closeDropdown();
            }}
            rightSection={
              queues.isFetching ? <Loader size={14} aria-label="Loading addresses" /> : <Combobox.Chevron />
            }
            rightSectionPointerEvents="none"
            error={unknown ? unknownHint : undefined}
          />
        </Combobox.Target>

        <Combobox.Dropdown>
          <div className={styles.filters}>
            <Chip.Group multiple value={types} onChange={(v) => setTypes(v as RoutingType[])}>
              <Group gap={6} wrap="nowrap">
                <Text size="xs" c="dimmed" component="span" id={`${label ?? 'address'}-type-filter`}>
                  Type
                </Text>
                {ROUTING_TYPES.map((t) => (
                  <Chip key={t} value={t} size="xs" variant="outline">
                    {t.toLowerCase()}
                  </Chip>
                ))}
              </Group>
            </Chip.Group>
          </div>

          <Combobox.Options>
            <div className={styles.options}>
              {queues.isError ? (
                <Combobox.Empty>
                  Could not read this cluster&rsquo;s queues: {queues.error.message} You can still
                  type an address by hand.
                </Combobox.Empty>
              ) : queues.isPending ? (
                <Combobox.Empty>Loading addresses…</Combobox.Empty>
              ) : options.length === 0 ? (
                <Combobox.Empty>
                  {types.length > 0
                    ? 'No address here matches that name and routing type. Clear the type filter, or type the name in full to use it anyway.'
                    : 'No address on this cluster matches that name. Type it in full to use it anyway — Studio will start tracing once it appears.'}
                </Combobox.Empty>
              ) : (
                options.map((o) => (
                  <Combobox.Option
                    value={o.address}
                    key={o.address}
                    // Built explicitly: the visual row separates these with flex
                    // gaps, which a screen reader reads as one run-on word.
                    aria-label={`${o.address}, ${o.routingType.toLowerCase()}, ${o.totalMessageCount.toLocaleString()} messages, on ${o.nodesPresent} of ${o.nodesTotal} nodes`}
                  >
                    <div className={styles.option}>
                      <span className={styles.name}>{o.address}</span>
                      <span className={styles.meta}>
                        <Text size="xs" span>
                          {o.routingType.toLowerCase()}
                        </Text>
                        <Text size="xs" span>
                          {o.totalMessageCount.toLocaleString()} msg
                        </Text>
                        <Text size="xs" span>
                          {o.nodesPresent}/{o.nodesTotal} nodes
                        </Text>
                      </span>
                    </div>
                  </Combobox.Option>
                ))
              )}
            </div>
          </Combobox.Options>
        </Combobox.Dropdown>
      </Combobox>
    </div>
  );
}

import { useMemo } from 'react';
import { Anchor, Code, Stack, TagsInput, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';

import { useQueues } from '../api/client.ts';

const SUGGESTION_LIMIT = 300;
const PREVIEW_LIMIT = 6;

/** A pattern is anything containing `*`; everything else is a literal address. */
function isPattern(entry: string): boolean {
  return entry.includes('*');
}

/**
 * Mirrors the backend's `ReplyAddressResolver.compile`: `*` matches any run of
 * characters, every other character is literal, anchored at both ends. Kept in step
 * with it deliberately — an operator shown one set of matches whose broker is then
 * browsed for another has been told a lie about what is traced.
 */
function globToRegExp(glob: string): RegExp {
  const parts = glob.split('*').map((part) => part.replace(/[.+?^${}()|[\]\\]/g, '\\$&'));
  return new RegExp(`^${parts.join('.*')}$`, 's');
}

/** What a set of literals and globs currently expands to, given the addresses we know. */
function resolveAgainst(entries: string[], known: string[]): string[] {
  const out = new Set<string>();
  for (const entry of entries) {
    if (!isPattern(entry)) {
      out.add(entry);
      continue;
    }
    const re = globToRegExp(entry);
    for (const address of known) {
      if (re.test(address)) out.add(address);
    }
  }
  return [...out];
}

export interface ReplyAddressesInputProps {
  clusterId: string;
  value: string[];
  onChange: (value: string[]) => void;
  label?: string;
  w?: number | string;
}

/**
 * The reply half of an expectation: a set of literal addresses or `*` globs.
 *
 * A single field could not hold the answer for either cluster Studio has been
 * pointed at — both answer on a shared reply queue per responder, named per broker
 * node in one and per client host in the other — so an operator faced with one box
 * left it empty, which is the one value that makes a shared-queue flow impossible to
 * join. This input therefore does two things a plain text field cannot: it takes
 * several entries, and it distinguishes "I meant temporary queues" from "I have not
 * filled this in" by saying out loud what an empty set means.
 */
export function ReplyAddressesInput({
  clusterId,
  value,
  onChange,
  label = 'Reply addresses',
  w,
}: ReplyAddressesInputProps) {
  // The address list is a background fact, not a per-keystroke query: matches are
  // computed here, so typing costs nothing over the wire.
  const queues = useQueues(clusterId, { size: SUGGESTION_LIMIT });
  const [entries] = useDebouncedValue(value, 200);

  const known = useMemo(() => {
    const addresses = new Set<string>();
    for (const row of queues.data?.data ?? []) addresses.add(row.address);
    return [...addresses].sort();
  }, [queues.data]);

  const resolved = useMemo(() => resolveAgainst(entries, known), [entries, known]);
  const unmatched = useMemo(
    () =>
      entries.filter((entry) => {
        if (!isPattern(entry)) return false;
        const re = globToRegExp(entry);
        return !known.some((address) => re.test(address));
      }),
    [entries, known],
  );

  const preview = resolved.slice(0, PREVIEW_LIMIT).join(', ');
  const overflow = resolved.length - PREVIEW_LIMIT;

  return (
    <Stack gap={4} w={w}>
      <TagsInput
        label={label}
        placeholder={value.length === 0 ? 'orders.reply.*' : undefined}
        value={value}
        onChange={onChange}
        data={known}
        splitChars={[',', ' ']}
        clearable
        aria-describedby="reply-addresses-help"
      />
      <Text id="reply-addresses-help" size="xs" c="dimmed">
        {value.length === 0 ? (
          <>
            Empty means replies arrive on a <strong>temporary queue</strong> named by each
            request&rsquo;s <Code>replyTo</Code>. If your responders answer on a shared queue
            instead, name it here — otherwise no reply can be joined to its request.
          </>
        ) : (
          <>
            <Code>*</Code> matches any run of characters, so <Code>orders.reply.*</Code> covers one
            reply queue per responder, including ones that do not exist yet. Matching is anchored,
            and Artemis&rsquo;s <Code>#</Code> is not a wildcard here.
          </>
        )}
      </Text>
      {value.length > 0 && !queues.isError ? (
        <Text size="xs" c={resolved.length === 0 ? 'orange' : 'dimmed'}>
          {resolved.length === 0
            ? 'Matches nothing on this cluster yet — tracing begins when a matching queue appears.'
            : `Resolves to ${resolved.length} address${resolved.length === 1 ? '' : 'es'}: ${preview}${
                overflow > 0 ? ` and ${overflow} more` : ''
              }`}
        </Text>
      ) : null}
      {unmatched.length > 0 && resolved.length > 0 ? (
        <Text size="xs" c="orange">
          Nothing matches {unmatched.map((p) => `"${p}"`).join(', ')} yet.
        </Text>
      ) : null}
      {queues.isError ? (
        <Text size="xs" c="dimmed">
          Could not read this cluster&rsquo;s addresses, so matches are not shown here. Patterns are
          still saved, and the server resolves them.{' '}
          <Anchor component="button" type="button" size="xs" onClick={() => void queues.refetch()}>
            Retry
          </Anchor>
        </Text>
      ) : null}
    </Stack>
  );
}

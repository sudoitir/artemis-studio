import { Anchor, Code, Stack, TagsInput, Text } from '@mantine/core';

import { useReplyAddressResolution } from './useReplyAddressResolution.ts';

const PREVIEW_LIMIT = 6;

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
 *
 * The saying-out-loud is {@link ReplyAddressesHelp}, which the caller renders below
 * the form row rather than under the field: four lines of prose inside a
 * bottom-aligned row was what pushed this control above every other one on the form.
 */
export function ReplyAddressesInput({
  clusterId,
  value,
  onChange,
  label = 'Reply addresses',
  w,
}: ReplyAddressesInputProps) {
  const { known } = useReplyAddressResolution(clusterId, value);

  return (
    <TagsInput
      label={label}
      placeholder={value.length === 0 ? 'orders.reply.*' : undefined}
      value={value}
      onChange={onChange}
      data={known}
      splitChars={[',', ' ']}
      clearable
      w={w}
      aria-describedby="reply-addresses-help"
    />
  );
}

/** What an empty set means, what a glob covers, and what it resolves to right now. */
export function ReplyAddressesHelp({ clusterId, value }: { clusterId: string; value: string[] }) {
  const { resolved, unmatched, isError, retry } = useReplyAddressResolution(clusterId, value);

  const preview = resolved.slice(0, PREVIEW_LIMIT).join(', ');
  const overflow = resolved.length - PREVIEW_LIMIT;

  return (
    <Stack gap={4}>
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
      {value.length > 0 && !isError ? (
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
      {isError ? (
        <Text size="xs" c="dimmed">
          Could not read this cluster&rsquo;s addresses, so matches are not shown here. Patterns are
          still saved, and the server resolves them.{' '}
          <Anchor component="button" type="button" size="xs" onClick={retry}>
            Retry
          </Anchor>
        </Text>
      ) : null}
    </Stack>
  );
}

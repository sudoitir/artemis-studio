import { useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  CopyButton,
  Drawer,
  Group,
  Loader,
  SegmentedControl,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';

import { useMessageDetail, type MessageDetailView } from './api.ts';
import { HexDump } from './HexDump.tsx';
import { detectPayload, messageTypeName, unavailableMessage } from './payload.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { GovernedValue, RedactionMarks, WithheldNotice } from '../../ui/RedactedValue.tsx';
import { redactionsAt } from '../../ui/redactions.ts';

type Redactions = MessageDetailView['redactions'];

/**
 * Raises the per-message body/property cap. Mirrors
 * {@code BrokerXmlSnippets.forMessageBodyLimit()} on the backend — shown next to
 * a truncated body so the operator has the exact change to make (non-negotiable
 * #5). This is a per-message disclosure, not a capability gate.
 */
const RAISE_LIMIT_SNIPPET = `<address-settings>
  <address-setting match="#">
    <management-message-attribute-size-limit>-1</management-message-attribute-size-limit>
  </address-setting>
</address-settings>`;

function PropertyTable({
  title,
  entries,
  redactions,
}: {
  title: string;
  entries: [string, unknown][];
  redactions: Redactions;
}) {
  if (entries.length === 0) return null;
  return (
    <Stack gap={4}>
      <Text size="xs" fw={600} c="dimmed">
        {title}
      </Text>
      <Table withRowBorders={false} verticalSpacing={2}>
        <Table.Tbody>
          {entries.map(([k, v]) => (
            <Table.Tr key={k}>
              <Table.Td w="40%">
                <Text size="xs" ff="monospace">
                  {k}
                </Text>
              </Table.Td>
              <Table.Td>
                <GovernedValue value={v} redactions={redactionsAt(redactions, 'PROPERTY', k)} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

/**
 * The body, with its format named and — when the format actually parsed — indented
 * and highlighted. Everything here is client-side: the body is already in the
 * browser, so nothing is sent back to be classified.
 */
function MessageBody({
  body,
  bodyEncoding,
  contentType,
  bodyTruncated,
  stringProperties,
  messageId,
  redactions,
  withheld,
}: {
  body: string | null;
  bodyEncoding: string;
  contentType?: string | null;
  bodyTruncated: boolean;
  stringProperties: Record<string, string>;
  messageId: number;
  redactions: Redactions;
  withheld: MessageDetailView['withheld'];
}) {
  const bodyRedactions = redactionsAt(redactions, 'BODY');
  const masked = bodyRedactions.some((r) => !r.clear);
  const [view, setView] = useState<'formatted' | 'raw'>('formatted');
  const detected = useMemo(
    () => detectPayload({ body, bodyEncoding, contentType, bodyTruncated, stringProperties }),
    [body, bodyEncoding, contentType, bodyTruncated, stringProperties],
  );

  const raw = body ?? '';
  const shown = view === 'formatted' && detected.formatted !== null ? detected.formatted : raw;
  const note = unavailableMessage(detected);

  const download = () => {
    const blob = new Blob([raw], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `message-${messageId}.${detected.format === 'json' ? 'json' : detected.format === 'xml' ? 'xml' : 'txt'}`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <Stack gap={4}>
      <Group gap="xs" justify="space-between">
        <Group gap="xs">
          <Text size="xs" fw={600} c="dimmed">
            Body
          </Text>
          <Badge size="xs" variant="light" color={detected.bytes ? 'teal' : 'gray'}>
            {detected.label}
          </Badge>
          {detected.source === 'declared' ? (
            <Text size="xs" c="dimmed">
              declared by the producer
            </Text>
          ) : null}
        </Group>
        <Group gap={4}>
          {detected.formatted !== null ? (
            <SegmentedControl
              size="xs"
              value={view}
              onChange={(v) => setView(v as 'formatted' | 'raw')}
              data={[
                { label: 'Formatted', value: 'formatted' },
                { label: 'Raw', value: 'raw' },
              ]}
            />
          ) : null}
          <CopyButton value={raw}>
            {({ copied, copy }) => (
              <Button size="compact-xs" variant="default" onClick={copy}>
                {copied ? 'Copied' : 'Copy'}
              </Button>
            )}
          </CopyButton>
          <Button size="compact-xs" variant="default" onClick={download}>
            Download
          </Button>
        </Group>
      </Group>

      <RedactionMarks redactions={bodyRedactions} />
      {masked ? (
        <Text size="xs" c="dimmed">
          Copy and Download carry the body as shown here, with sensitive values masked.
        </Text>
      ) : null}
      <WithheldNotice withheld={withheld} />

      {body === null && withheld.length > 0 ? null : detected.bytes ? (
        <HexDump bytes={detected.bytes} />
      ) : (
        <CodeHighlight
          code={shown || '(empty)'}
          language={(view === 'formatted' && detected.highlightLanguage) || 'text'}
        />
      )}

      {note ? (
        <Text size="xs" c="dimmed">
          {note}
          {detected.unavailable === 'truncated' ? ' See the truncation notice below.' : ''}
        </Text>
      ) : null}
      {bodyEncoding === 'BASE64' ? (
        <Text size="xs" c="dimmed">
          Shown as bytes — the Core client returned the exact body, not a stringified copy.
        </Text>
      ) : null}
    </Stack>
  );
}

export function MessageDetailPanel({
  clusterId,
  queueName,
  messageId,
  node,
  filter,
  onClose,
}: {
  clusterId: string;
  queueName: string;
  messageId: string | null;
  node?: string;
  filter?: string;
  onClose: () => void;
}) {
  const detail = useMessageDetail(clusterId, queueName, messageId, node, filter);
  const m = detail.data;
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  useDisplayZone();

  return (
    <Drawer
      opened={messageId !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={messageId ? `Message ${messageId}` : ''}
    >
      {detail.isPending ? (
        <Loader size="sm" />
      ) : detail.isError ? (
        <Alert color="red" variant="light" title={detail.error.title}>
          {detail.error.message}
        </Alert>
      ) : m ? (
        <Stack gap="md">
          <Group gap="xs">
            <Badge variant="light">{messageTypeName(m.type)}</Badge>
            <Badge variant="light" color="gray">
              {m.durable ? 'durable' : 'non-durable'}
            </Badge>
            <Badge variant="light" color="gray">
              priority {m.priority}
            </Badge>
            <Badge variant="light" color="gray">
              {m.size} bytes
            </Badge>
            <Badge
              variant="light"
              color={m.transport === 'CORE' ? 'teal' : 'blue'}
              title={
                m.transport === 'CORE'
                  ? 'Read faithfully over the Core protocol client'
                  : 'Read over the Jolokia management channel'
              }
            >
              via {m.transport === 'CORE' ? 'Core' : 'Jolokia'}
            </Badge>
          </Group>

          <Table withRowBorders={false} verticalSpacing={2}>
            <Table.Tbody>
              <Table.Tr>
                <Table.Td w="40%">
                  <Text size="xs" c="dimmed">
                    Enqueued
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">
                    {absoluteLabel(m.timestamp)}
                  </Text>
                </Table.Td>
              </Table.Tr>
              <Table.Tr>
                <Table.Td>
                  <Text size="xs" c="dimmed">
                    Expiration
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">
                    {m.expiration > 0 ? absoluteLabel(m.expiration) : 'never'}
                  </Text>
                </Table.Td>
              </Table.Tr>
              {m.groupId ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Group
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <GovernedValue value={m.groupId} redactions={redactionsAt(m.redactions, 'HEADER', 'groupId')} />
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {m.correlationId ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      Correlation ID
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <GovernedValue
                      value={m.correlationId}
                      redactions={redactionsAt(m.redactions, 'HEADER', 'correlationId')}
                    />
                  </Table.Td>
                </Table.Tr>
              ) : null}
              {m.userId ? (
                <Table.Tr>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      User ID
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <GovernedValue value={m.userId} redactions={redactionsAt(m.redactions, 'HEADER', 'userId')} />
                  </Table.Td>
                </Table.Tr>
              ) : null}
            </Table.Tbody>
          </Table>

          <PropertyTable title="String properties" entries={Object.entries(m.stringProperties)} redactions={m.redactions} />
          <PropertyTable title="Integer properties" entries={Object.entries(m.intProperties)} redactions={m.redactions} />
          <PropertyTable title="Long properties" entries={Object.entries(m.longProperties)} redactions={m.redactions} />
          <PropertyTable title="Double properties" entries={Object.entries(m.doubleProperties)} redactions={m.redactions} />
          <PropertyTable title="Boolean properties" entries={Object.entries(m.booleanProperties)} redactions={m.redactions} />

          <MessageBody
            body={m.body ?? null}
            bodyEncoding={m.bodyEncoding}
            contentType={m.contentType}
            bodyTruncated={m.bodyTruncated}
            stringProperties={m.stringProperties}
            messageId={m.messageId}
            redactions={m.redactions}
            withheld={m.withheld}
          />

          {m.bodyTruncated ? (
            <Alert color="yellow" variant="light" title="This message is truncated">
              <Stack gap="xs">
                <Text size="sm">
                  The broker clipped this message's body and property values at{' '}
                  {m.observedLimitBytes ?? 'its'} bytes
                  (<code>management-message-attribute-size-limit</code>). To see the whole message,
                  raise the limit in <code>broker.xml</code> and re-browse, or connect the Core
                  client so Studio can read it faithfully:
                </Text>
                <CodeHighlight code={RAISE_LIMIT_SNIPPET} language="xml" />
              </Stack>
            </Alert>
          ) : null}
        </Stack>
      ) : null}
    </Drawer>
  );
}

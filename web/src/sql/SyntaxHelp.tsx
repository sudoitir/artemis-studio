import { Button, Code, Divider, Group, Modal, Stack, Table, Text, Title } from '@mantine/core';

import { COLUMNS, EVALUATION_WORDS, EXAMPLES, FUNCTIONS } from './catalogue.ts';

/**
 * The dialect, in the page rather than behind a documentation link. An operator
 * writing a query at 3am should not have to leave the console to find out what
 * `props.` means or why one predicate is free and another is not.
 *
 * <p>Mantine's `Modal` traps focus while open and returns it to whatever opened
 * it on close, so the keyboard path in and back out is the component's, not a
 * hand-rolled one.
 */
export function SyntaxHelp({
  opened,
  onClose,
  onLoadExample,
}: {
  opened: boolean;
  onClose: () => void;
  /** Loads an example into the editor and closes — one action, per the spec. */
  onLoadExample: (sql: string) => void;
}) {
  return (
    <Modal opened={opened} onClose={onClose} title="The console's dialect" size="xl">
      <Stack gap="lg">
        <Stack gap="xs">
          <Text size="sm">
            A restricted subset of SQL: <Code>SELECT</Code> only, one <Code>FROM</Code>, no join, no
            subquery, no union. It cannot express a mutation — acting on a result row goes back
            through the message operations, which carry their own dry run and audit record.
          </Text>
          <Text size="sm">
            A queue name is not a SQL identifier — <Code>ORDER.IN</Code> is a reserved word and a
            dot — so it is always double-quoted. Wildcards are Artemis&apos;, not SQL&apos;s:{' '}
            <Code>*</Code> matches one dot-delimited level and <Code>#</Code> matches many.
          </Text>
        </Stack>

        <Stack gap="xs">
          <Title order={5}>Where the query reads</Title>
          <Text size="sm">
            The schema qualifier picks the backend. <Code>FROM broker.&quot;Q&quot;</Code> reads the
            live brokers — current truth. <Code>FROM index.&quot;Q&quot;</Code> reads the historical
            index, which still holds messages that have since been consumed, and only covers queues
            with a subscription. <Code>FROM &quot;Q&quot;</Code> lets the planner choose, and the
            plan says which it picked.
          </Text>
        </Stack>

        <Stack gap="xs">
          <Title order={5}>What a predicate costs</Title>
          <Text size="sm">
            This is the one thing worth knowing before running anything. A predicate over a header or
            an application property becomes a JMS selector, so the broker filters and Studio never
            sees the messages that did not match. A predicate over the body cannot be pushed down at
            all: every message the broker returns has to be read and examined. The plan strip above
            the editor says which of the two your query is, before it runs.
          </Text>
          <Text size="sm">
            One trap worth stating: a predicate that is free on its own stops being free inside an{' '}
            <Code>OR</Code> with a body predicate. Pushing down one side of an <Code>OR</Code> would
            narrow the set the other side gets to see, so the whole disjunction is scanned.
          </Text>
        </Stack>

        <Divider />

        <Stack gap="xs">
          <Title order={5}>Columns</Title>
          <Table verticalSpacing={4} withRowBorders={false}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Column</Table.Th>
                <Table.Th>Cost</Table.Th>
                <Table.Th>Meaning</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {COLUMNS.map((c) => (
                <Table.Tr key={c.name}>
                  <Table.Td>
                    <Code>{c.name}</Code>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      {EVALUATION_WORDS[c.evaluation]}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs">
                      {c.description}
                      {c.indexOnly ? ' Index only.' : ''}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Text size="sm">
            Application properties are addressed by name as <Code>props.tenant</Code>, and a JSON
            field inside the body as <Code>body-&gt;&gt;&apos;orderId&apos;</Code>. The only
            functions the dialect accepts are{' '}
            {FUNCTIONS.map((f) => (
              <Code key={f}>{f}()</Code>
            ))}
            , plus <Code>interval</Code> in a relative time.
          </Text>
        </Stack>

        <Divider />

        <Stack gap="xs">
          <Title order={5}>Examples</Title>
          {EXAMPLES.map((example) => (
            <Stack key={example.title} gap={4}>
              <Group justify="space-between" align="flex-start" wrap="nowrap">
                <Text size="sm" fw={600}>
                  {example.title}
                </Text>
                <Button
                  size="compact-xs"
                  variant="default"
                  onClick={() => {
                    onLoadExample(example.sql);
                    onClose();
                  }}
                >
                  Load into editor
                </Button>
              </Group>
              <Code block>{example.sql}</Code>
              <Text size="xs" c="dimmed">
                {example.note}
              </Text>
            </Stack>
          ))}
        </Stack>
      </Stack>
    </Modal>
  );
}

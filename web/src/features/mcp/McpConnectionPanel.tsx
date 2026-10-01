import { Button, Code, CopyButton, Group, Stack, Text, TextInput } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { IconCopy } from '@tabler/icons-react';

import { branding } from '../../branding.ts';
import { notify } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import classes from './McpConnectionPanel.module.css';

const COPY = { verb: 'Copy', past: 'Copied', progressive: 'Copying' } as const;

/**
 * How to point an MCP client at this instance (ADR-0045, ADR-0046).
 *
 * <p>The key placeholder is deliberate. A key's value exists exactly once, at the
 * moment it is minted; this panel is reachable at any time and could only ever
 * print a stale or fabricated one. Showing `<your-api-key>` is honest, and it
 * keeps a screenshot of this page from being a credential.
 */
export function McpConnectionPanel() {
  const endpoint = `${globalThis.location.origin}/mcp`;
  const config = JSON.stringify(
    {
      mcpServers: {
        'artemis-studio': {
          // Clients reject an entry that gives a url without saying how to speak
          // to it. Studio's endpoint is streamable HTTP.
          type: 'http',
          url: endpoint,
          headers: { Authorization: 'Bearer <your-api-key>' },
        },
      },
    },
    null,
    2,
  );

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {branding.productName} speaks the Model Context Protocol, so an assistant can read your clusters and run the
        same guarded operations you can — never more than the key's permissions allow.
      </Text>

      <Group align="flex-end">
        <TextInput className={classes.endpoint} label="Endpoint" value={endpoint} readOnly />
        <CopyButton value={endpoint}>
          {({ copy }) => (
            <Button
              variant="default"
              leftSection={<IconCopy size="1rem" aria-hidden />}
              onClick={() => {
                copy();
                notify.succeeded({ action: COPY, subject: 'the endpoint' });
              }}
            >
              Copy endpoint
            </Button>
          )}
        </CopyButton>
      </Group>

      <Section title="Client configuration" headingLevel={3}>
        <CodeHighlight code={config} language="json" />
      </Section>

      <Text size="xs" c="dimmed">
        Create a key above, choose the permissions it should carry, and paste its value in place of{' '}
        <Code>&lt;your-api-key&gt;</Code>. Mutations dry-run by default and destructive ones need an explicit
        confirmation.
      </Text>
    </Stack>
  );
}

import { ActionIcon, CopyButton, Group, Stack, Text, TextInput } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { IconCopy } from '@tabler/icons-react';

import { branding } from '../branding.ts';

/**
 * How to point an MCP client at this instance (ADR-0045, ADR-0046).
 *
 * <p>The key placeholder is deliberate. A key's value exists exactly once, at the
 * moment it is minted; this panel is reachable at any time and could only ever
 * print a stale or fabricated one. Showing `<your-api-key>` is honest, and it
 * keeps a screenshot of this page from being a credential.
 */
export function McpConnectionPanel() {
  const endpoint = `${window.location.origin}/mcp`;
  const config = JSON.stringify(
    {
      mcpServers: {
        'artemis-studio': {
          url: endpoint,
          headers: { Authorization: 'Bearer <your-api-key>' },
        },
      },
    },
    null,
    2,
  );

  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        {branding.productName} speaks the Model Context Protocol, so an assistant can read
        your clusters and run the same guarded operations you can — never more than the
        key's permissions allow.
      </Text>

      <Group align="flex-end">
        <TextInput
          label="Endpoint"
          value={endpoint}
          readOnly
          ff="monospace"
          style={{ flex: 1 }}
        />
        <CopyButton value={endpoint}>
          {({ copy }) => (
            <ActionIcon size="lg" variant="default" onClick={copy} aria-label="Copy endpoint">
              <IconCopy size={16} />
            </ActionIcon>
          )}
        </CopyButton>
      </Group>

      <div>
        <Text size="sm" fw={500} mb={4}>
          Client configuration
        </Text>
        <CodeHighlight code={config} language="json" />
      </div>

      <Text size="xs" c="dimmed">
        Create a key above, choose the permissions it should carry, and paste its value in
        place of <Text component="span" ff="monospace" size="xs">&lt;your-api-key&gt;</Text>.
        Mutations dry-run by default and destructive ones need an explicit confirmation.
      </Text>
    </Stack>
  );
}

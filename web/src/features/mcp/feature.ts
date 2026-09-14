import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { McpSection } from './sections.tsx';

/** The MCP server: an assistant's tools, prompts and resources over this instance. */
export const mcpFeature = defineFeature({
  contract: CONTRACT,
  id: 'mcp',
  slots: {
    'account.sections': [{ id: 'mcp-connection', order: 30, title: 'MCP connection', Component: McpSection }],
  },
});

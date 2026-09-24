import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Text } from '@mantine/core';

interface Props {
  pluginId: string;
  /** What to render instead: nothing for a badge or a palette source, a sentence for a panel. */
  quiet?: boolean;
  children: ReactNode;
}

/**
 * Keeps a plugin's failure to itself (ADR-0100): a plugin component that throws while rendering
 * is replaced by a sentence naming the plugin — or by nothing, where a sentence would not fit —
 * and the screen around it keeps working.
 */
export class PluginBoundary extends Component<Props, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error(`Plugin ${this.props.pluginId} failed to render`, error, info.componentStack);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    if (this.props.quiet) return null;
    return (
      <Text size="sm" c="dimmed" role="status">
        The {this.props.pluginId} plugin could not show this part of the screen. The rest of the page is
        unaffected; an administrator can see the plugin's state under Administration → Plugins.
      </Text>
    );
  }
}

import { Component, type ErrorInfo, type ReactNode } from 'react';
import { ErrorState } from '../../ui/ErrorState.tsx';

interface Props {
  pluginId: string;
  /** What to render instead: nothing for a badge or a palette source, a sentence for a panel. */
  quiet?: boolean;
  children: ReactNode;
}

/** What a boundary says of a thrown value: an Error's message, a string as it is, anything else by its kind. */
function reasonOf(error: unknown): string {
  if (error instanceof Error) return error.message;
  return typeof error === 'string' ? error : `a ${typeof error} was thrown`;
}

/**
 * Keeps a plugin's failure to itself (ADR-0100): a plugin component that throws while rendering
 * is replaced by a sentence naming the plugin — or by nothing, where a sentence would not fit —
 * and the screen around it keeps working.
 */
export class PluginBoundary extends Component<Props, { error: unknown }> {
  state: { error: unknown } = { error: null };

  static getDerivedStateFromError(error: unknown) {
    return { error: error ?? new Error('A plugin component threw without a message') };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error(`Plugin ${this.props.pluginId} failed to render`, error, info.componentStack);
  }

  render() {
    if (this.state.error === null) return this.props.children;
    if (this.props.quiet) return null;
    const { error } = this.state;
    const reason = reasonOf(error);
    return (
      <ErrorState
        variant="inline"
        error={new Error(`The ${this.props.pluginId} plugin could not show this part of the screen: ${reason}`)}
        onRetry={() => this.setState({ error: null })}
      />
    );
  }
}

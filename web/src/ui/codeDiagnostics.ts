import type { Diagnostic } from '@codemirror/lint';
import type { EditorState } from '@codemirror/state';

/** A problem at a 1-based line and column, as a server validator reports it. */
export interface CodeDiagnostic {
  line: number;
  column: number;
  message: string;
  severity: 'error' | 'warning';
}

/** A 1-based line and column, clamped into the document, as a one-character range. */
export function toCodeMirror(state: EditorState, d: CodeDiagnostic): Diagnostic {
  const line = state.doc.line(Math.min(Math.max(d.line, 1), state.doc.lines));
  const from = Math.min(line.from + Math.max(d.column - 1, 0), line.to);
  return { from, to: Math.min(from + 1, state.doc.length), severity: d.severity, message: d.message };
}

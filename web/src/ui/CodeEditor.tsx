import { useEffect, useId, useRef } from 'react';
import { Text, VisuallyHidden } from '@mantine/core';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { json } from '@codemirror/lang-json';
import { yaml } from '@codemirror/lang-yaml';
import { syntaxHighlighting } from '@codemirror/language';
import { lintGutter, setDiagnostics } from '@codemirror/lint';
import { EditorState, type Extension } from '@codemirror/state';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';

import { codeHighlight, codeTheme } from './codeMirrorTheme.ts';
import { toCodeMirror, type CodeDiagnostic } from './codeDiagnostics.ts';

export type { CodeDiagnostic };

export interface CodeEditorProps {
  value: string;
  /** Omitted, the editor is read-only. */
  onChange?: (value: string) => void;
  language: 'yaml' | 'json';
  diagnostics?: CodeDiagnostic[];
  readOnly?: boolean;
  /** The visible label, which is also the editor's accessible name. */
  label: string;
  minHeight?: number;
}

/**
 * A document editor for YAML or JSON: CodeMirror with line numbers and a lint
 * gutter, coloured from `theme.css` like the SQL console. It validates nothing
 * itself. The caller passes the server's diagnostics, which are marked at their
 * line and column and summarised in a live region, so a screen reader hears
 * that the document has problems without hunting for the marks.
 */
export function CodeEditor({
  value,
  onChange,
  language,
  diagnostics = [],
  readOnly = false,
  label,
  minHeight = 240,
}: CodeEditorProps) {
  const labelId = useId();
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  // The extensions are built once; this keeps the change handler current without
  // tearing the editor down and losing the cursor on every parent render.
  const latest = useRef(onChange);
  latest.current = onChange;
  const editable = !readOnly && onChange !== undefined;

  useEffect(() => {
    if (!host.current) return;
    const extensions: Extension[] = [
      lineNumbers(),
      lintGutter(),
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap]),
      language === 'yaml' ? yaml() : json(),
      syntaxHighlighting(codeHighlight),
      EditorState.readOnly.of(!editable),
      EditorView.editable.of(editable),
      EditorView.contentAttributes.of({ 'aria-labelledby': labelId, role: 'textbox', 'aria-multiline': 'true' }),
      EditorView.theme({ '.cm-content, .cm-gutter': { minHeight: `${minHeight}px` } }),
      codeTheme,
      EditorView.updateListener.of((update) => {
        if (update.docChanged) latest.current?.(update.state.doc.toString());
      }),
    ];
    const editor = new EditorView({
      state: EditorState.create({ doc: value, extensions }),
      parent: host.current,
    });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
    // Built once per language and mode; `value` and `diagnostics` are synced below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [labelId, language, editable, minHeight]);

  // An external change (a load, a reset) is pushed in. A change the editor made is
  // already in the document, and replacing it would move the cursor mid-typing.
  useEffect(() => {
    const editor = view.current;
    if (!editor) return;
    const current = editor.state.doc.toString();
    if (current !== value) editor.dispatch({ changes: { from: 0, to: current.length, insert: value } });
  }, [value]);

  useEffect(() => {
    const editor = view.current;
    if (!editor) return;
    editor.dispatch(setDiagnostics(editor.state, diagnostics.map((d) => toCodeMirror(editor.state, d))));
  }, [diagnostics, value]);

  const errors = diagnostics.filter((d) => d.severity === 'error').length;
  const warnings = diagnostics.length - errors;

  return (
    <div>
      <Text id={labelId} component="label" size="xs" fw={600} c="dimmed" display="block" mb={4}>
        {label}
      </Text>
      <div ref={host} />
      <VisuallyHidden aria-live="polite">
        {diagnostics.length === 0
          ? ''
          : `${errors} ${errors === 1 ? 'error' : 'errors'}, ${warnings} ${warnings === 1 ? 'warning' : 'warnings'}. ` +
            `First, line ${diagnostics[0].line}: ${diagnostics[0].message}`}
      </VisuallyHidden>
    </div>
  );
}

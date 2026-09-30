import { useEffect, useId, useRef } from 'react';
import { Text, VisuallyHidden } from '@mantine/core';
import { closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab, temporarilySetTabFocusMode } from '@codemirror/commands';
import { json } from '@codemirror/lang-json';
import { yaml } from '@codemirror/lang-yaml';
import {
  bracketMatching,
  codeFolding,
  foldGutter,
  foldKeymap,
  indentOnInput,
  indentUnit,
  syntaxHighlighting,
} from '@codemirror/language';
import { lintGutter, setDiagnostics } from '@codemirror/lint';
import { highlightSelectionMatches, search, searchKeymap } from '@codemirror/search';
import { EditorState, type Extension } from '@codemirror/state';
import {
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
} from '@codemirror/view';

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
  /** Past this height the editor scrolls inside itself. */
  maxHeight?: number;
  /** Wrap long lines instead of scrolling them sideways. */
  lineWrapping?: boolean;
}

/** What a screen reader hears when the diagnostics change: the counts, then the first one. */
function diagnosticsSummary(diagnostics: CodeDiagnostic[]): string {
  if (diagnostics.length === 0) return '';
  const errors = diagnostics.filter((d) => d.severity === 'error').length;
  const warnings = diagnostics.length - errors;
  const errorsLabel = errors === 1 ? 'error' : 'errors';
  const warningsLabel = warnings === 1 ? 'warning' : 'warnings';
  return `${errors} ${errorsLabel}, ${warnings} ${warningsLabel}. First, line ${diagnostics[0].line}: ${diagnostics[0].message}`;
}

/**
 * A document editor for YAML or JSON: CodeMirror with line numbers, folding, a
 * lint gutter, bracket matching and search, coloured from `theme.css` like the
 * SQL console. It validates nothing itself. The caller passes the server's
 * diagnostics, which are marked at their line and column and summarised in a
 * live region, so a screen reader hears that the document has problems without
 * hunting for the marks.
 *
 * Tab indents, so an editable editor would otherwise trap the keyboard. Escape
 * hands the next Tab back to the browser (CodeMirror's tab-focus mode), and the
 * hint under the editor says so.
 */
export function CodeEditor({
  value,
  onChange,
  language,
  diagnostics = [],
  readOnly = false,
  label,
  minHeight = 240,
  maxHeight,
  lineWrapping = false,
}: Readonly<CodeEditorProps>) {
  const labelId = useId();
  const hintId = useId();
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
      highlightActiveLineGutter(),
      highlightSpecialChars(),
      foldGutter(),
      codeFolding(),
      lintGutter(),
      history(),
      bracketMatching(),
      highlightSelectionMatches(),
      search({ top: false }),
      language === 'yaml' ? yaml() : json(),
      indentUnit.of('  '),
      syntaxHighlighting(codeHighlight),
      EditorState.readOnly.of(!editable),
      EditorView.editable.of(editable),
      EditorView.contentAttributes.of({
        'aria-labelledby': labelId,
        'aria-describedby': hintId,
        role: 'textbox',
        'aria-multiline': 'true',
      }),
      EditorView.theme({
        '.cm-content, .cm-gutter': { minHeight: `${minHeight}px` },
        ...(maxHeight ? { '&': { maxHeight: `${maxHeight}px` } } : {}),
      }),
      codeTheme,
      EditorView.updateListener.of((update) => {
        if (update.docChanged) latest.current?.(update.state.doc.toString());
      }),
    ];
    if (lineWrapping) extensions.push(EditorView.lineWrapping);
    if (editable) {
      extensions.push(
        highlightActiveLine(),
        closeBrackets(),
        indentOnInput(),
        keymap.of([
          ...closeBracketsKeymap,
          ...defaultKeymap,
          ...searchKeymap,
          ...historyKeymap,
          ...foldKeymap,
          indentWithTab,
          // After the default Escape (which collapses a multi-range selection) declines.
          { key: 'Escape', run: temporarilySetTabFocusMode },
        ]),
      );
    } else {
      extensions.push(keymap.of([...defaultKeymap, ...searchKeymap, ...foldKeymap]));
    }
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
  }, [labelId, hintId, language, editable, minHeight, maxHeight, lineWrapping]);

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
    editor.dispatch(
      setDiagnostics(
        editor.state,
        diagnostics.map((d) => toCodeMirror(editor.state, d)),
      ),
    );
  }, [diagnostics, value]);

  return (
    <div>
      <Text id={labelId} component="label" size="xs" fw={600} c="dimmed" display="block" mb={4}>
        {label}
      </Text>
      <div ref={host} style={{ minInlineSize: 0 }} />
      <Text id={hintId} size="xs" c="dimmed" mt={4}>
        {editable ? 'Tab indents. Press Escape, then Tab, to leave the editor. Ctrl+F searches.' : 'Ctrl+F searches.'}
      </Text>
      <VisuallyHidden aria-live="polite">{diagnosticsSummary(diagnostics)}</VisuallyHidden>
    </div>
  );
}

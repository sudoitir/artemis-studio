import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { foldable } from '@codemirror/language';
import { EditorView } from '@codemirror/view';
import { EditorState } from '@codemirror/state';

import { renderWithProviders } from '../test/render.tsx';
import { CodeEditor } from './CodeEditor.tsx';
import { toCodeMirror } from './codeDiagnostics.ts';

const DOC = 'apiVersion: v1\nkind: Flow\nspec:\n  mode: sometimes\n';

describe('CodeEditor', () => {
  it('names the editor by its visible label and shows the document', () => {
    renderWithProviders(<CodeEditor label="Flow document" language="yaml" value={DOC} onChange={vi.fn()} />);
    const editor = screen.getByRole('textbox', { name: 'Flow document' });
    expect(editor.textContent).toContain('kind: Flow');
  });

  it('announces the diagnostics it is given', () => {
    renderWithProviders(
      <CodeEditor
        label="Flow document"
        language="yaml"
        value={DOC}
        onChange={vi.fn()}
        diagnostics={[
          { line: 4, column: 9, message: 'mode must be tap or consume', severity: 'error' },
          { line: 2, column: 1, message: 'no limits', severity: 'warning' },
        ]}
      />,
    );
    expect(screen.getByText('1 error, 1 warning. First, line 4: mode must be tap or consume')).toBeTruthy();
  });

  it('is read-only without a change handler', () => {
    renderWithProviders(<CodeEditor label="Published" language="json" value="{}" />);
    const editor = screen.getByRole('textbox', { name: 'Published' });
    expect(editor.getAttribute('contenteditable')).toBe('false');
    // Still reachable by keyboard, so a document that scrolls can be read.
    expect(editor.tabIndex).toBe(0);
  });

  it('maps a 1-based line and column into the document, clamped', () => {
    const state = EditorState.create({ doc: DOC });
    expect(toCodeMirror(state, { line: 4, column: 9, message: 'x', severity: 'error' }).from).toBe(
      DOC.indexOf('sometimes'),
    );
    const past = toCodeMirror(state, { line: 99, column: 99, message: 'x', severity: 'error' });
    expect(past.from).toBeLessThanOrEqual(DOC.length);
  });

  it('highlights a key, its value and a comment differently', () => {
    renderWithProviders(
      <CodeEditor label="Doc" language="yaml" value={'queue: orders # the inbound queue\n'} onChange={vi.fn()} />,
    );
    const line = screen.getByRole('textbox', { name: 'Doc' }).querySelector('.cm-line')!;
    const classOf = (text: string) =>
      [...line.querySelectorAll('span')].find((span) => span.textContent === text)?.className;
    const key = classOf('queue');
    const value = classOf('orders');
    const comment = classOf('# the inbound queue');
    expect(key).toBeTruthy();
    expect(value).toBeTruthy();
    expect(comment).toBeTruthy();
    expect(new Set([key, value, comment]).size).toBe(3);
  });

  it('folds a YAML block', () => {
    renderWithProviders(<CodeEditor label="Doc" language="yaml" value={DOC} onChange={vi.fn()} />);
    const view = EditorView.findFromDOM(screen.getByRole('textbox', { name: 'Doc' }))!;
    const spec = view.state.doc.line(3);
    expect(foldable(view.state, spec.from, spec.to)).not.toBeNull();
  });

  it('indents on Tab, and lets Tab leave after Escape', () => {
    renderWithProviders(<CodeEditor label="Doc" language="yaml" value="a: 1" onChange={vi.fn()} />);
    const content = screen.getByRole('textbox', { name: 'Doc' });
    const view = EditorView.findFromDOM(content)!;
    view.dispatch({ selection: { anchor: 0 } });

    fireEvent.keyDown(content, { key: 'Tab', code: 'Tab', keyCode: 9 });
    expect(view.state.doc.toString()).toBe('  a: 1');

    fireEvent.keyDown(content, { key: 'Escape', code: 'Escape', keyCode: 27 });
    const tab = fireEvent.keyDown(content, { key: 'Tab', code: 'Tab', keyCode: 9 });
    expect(view.state.doc.toString()).toBe('  a: 1');
    // Not prevented: the browser moves focus on.
    expect(tab).toBe(true);
    expect(screen.getByText(/Press Escape, then Tab, to leave the editor/)).toBeTruthy();
  });
});

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
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
    expect(screen.getByRole('textbox', { name: 'Published' }).getAttribute('contenteditable')).toBe('false');
  });

  it('maps a 1-based line and column into the document, clamped', () => {
    const state = EditorState.create({ doc: DOC });
    expect(toCodeMirror(state, { line: 4, column: 9, message: 'x', severity: 'error' }).from).toBe(
      DOC.indexOf('sometimes'),
    );
    const past = toCodeMirror(state, { line: 99, column: 99, message: 'x', severity: 'error' });
    expect(past.from).toBeLessThanOrEqual(DOC.length);
  });
});
